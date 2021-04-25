import * as pulumi from "@pulumi/pulumi";
import * as aws from "@pulumi/aws";
import * as axios from "axios";
import { toUUID } from 'to-uuid'

const config = new pulumi.Config();

async function go() {

    const { sha, version } = await determineServerVersion()

    const securityGroup = await createMinecraftSecurityGroup()

    const { availabilityZones, minPrice } = await determineSpotPricePerHour()
    const maxPrice = minPrice * 1.15

    const record = await createRoutingRecord()

    const latestBucket = new aws.s3.Bucket('minecraft-current', {
        forceDestroy: false // prevent deleting latest version
    })
    const backupsBucket = new aws.s3.Bucket('minecraft-backups', {
        forceDestroy: true,
        lifecycleRules: [{
            abortIncompleteMultipartUploadDays: 1,
            enabled: true,
            expiration: { days: 30 }
        }]
    })

    const amazon_linux_2_ami = await aws.getAmi({
        mostRecent: true,
        filters: [
            {
                name: 'name',
                values: [config.require('ami_filter')]
            },
            {
                name: 'virtualization-type',
                values: ['hvm']
            }
        ],
        owners: ['amazon']
    })

    const userData = pulumi.interpolate `
#!/usr/bin/env bash
yum -y install java-11-amazon-corretto-headless

mkdir /minecraft
cd /minecraft

# pull down latest world backup -- ignore errors
set +e
aws s3 cp s3://${latestBucket.id}/world.zip /minecraft/world.zip
unzip world.zip
set -e

# pull down latest server.jar
wget --quiet https://launcher.mojang.com/v1/objects/${sha}/server.jar

echo '#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).
#Sat May 09 18:11:14 UTC 2020
eula=true' > eula.txt

# create the server files
echo "motd=${config.get('title') || 'Minecraft World!'}" > /minecraft/server.properties
echo "enforce-whitelist=true" >> /minecraft/server.properties
echo "white-list=true" >> /minecraft/server.properties
echo "force-gamemode=true" >> /minecraft/server.properties
echo "level-name=world" >> /minecraft/server.properties
echo "max-players=5" >> /minecraft/server.properties
echo "pvp=false" >> /minecraft/server.properties
echo "snooper-enabled=false" >> /minecraft/server.properties
echo "spawn-protection=0" >> /minecraft/server.properties
echo "view-distance=12" >> /minecraft/server.properties

echo "[Unit]" > /etc/systemd/system/minecraft.service
echo "Description=Minecraft Service" >> /etc/systemd/system/minecraft.service
echo "After=default.target" >> /etc/systemd/system/minecraft.service
echo "[Service]" >> /etc/systemd/system/minecraft.service
echo "Restart=on-failure" >> /etc/systemd/system/minecraft.service
echo "RestartSec=5s" >> /etc/systemd/system/minecraft.service
echo "WorkingDirectory=/minecraft" >> /etc/systemd/system/minecraft.service
echo "Type=simple" >> /etc/systemd/system/minecraft.service
echo "User=ec2-user" >> /etc/systemd/system/minecraft.service
echo "ExecStart=/usr/bin/java ${config.get('javaArgs') || ''} -jar /minecraft/server.jar nogui" >> /etc/systemd/system/minecraft.service

systemctl daemon-reload

echo '${await computeOperatorsFileString()}' > /minecraft/ops.json
echo '${await computePlayersFileString()}' > /minecraft/whitelist.json

# create automated backups
cat << EOF > /minecraft/backup.sh
cd /minecraft
zip -r -9 --filesync world.zip world/
aws s3 cp --no-progress world.zip s3://${latestBucket.id}/world.zip
EOF

chmod +x /minecraft/backup.sh

echo 'NOW=\`date -Iseconds\`
aws s3 cp --no-progress s3://${latestBucket.id}/world.zip "s3://${backupsBucket.id}/world-\$NOW.zip" --storage-class STANDARD_IA
' > /minecraft/copy.sh

chmod +x /minecraft/copy.sh
crontab<<EOF
*/15 * * * * /minecraft/backup.sh
0 */4 * * * /minecraft/copy.sh
EOF

chown -R ec2-user:ec2-user /minecraft

service minecraft start

IP=$(curl http://169.254.169.254/latest/meta-data/public-ipv4)
aws route53 change-resource-record-sets --hosted-zone-id ${record.zoneId} --change-batch "
{
    \\"Comment\\": \\"new instance\\", 
    \\"Changes\\": [{
        \\"Action\\": \\"UPSERT\\", 
        \\"ResourceRecordSet\\": {
            \\"Name\\": \\"minecraft.zach-jones.com\\", 
            \\"Type\\": \\"A\\", 
            \\"TTL\\": 5, 
            \\"ResourceRecords\\": [{\\"Value\\": \\"$IP\\"}]
        }
    }]
}
"
`

    const role = new aws.iam.Role('minecraft-role', {
        assumeRolePolicy: {
            Version: '2012-10-17',
            Statement: [
                {
                    Effect: 'Allow',
                    Principal: aws.iam.Principals.Ec2Principal,
                    Action: "sts:AssumeRole"
                }
            ]
        }
    })

    const policy = new aws.iam.RolePolicy('minecraft-role-policy', {
        role: role.id,
        policy: {
            Version: '2012-10-17',
            Statement: [
                {
                    Effect: 'Allow',
                    Action: "s3:*",
                    Resource: '*'
                },
                {
                    Effect: 'Allow',
                    Action: "route53:*",
                    Resource: '*'
                }
            ]
        }
    })

    const launch = new aws.ec2.LaunchConfiguration('minecraft_launch_configuration', {
        associatePublicIpAddress: true,
        imageId: amazon_linux_2_ami.id,
        keyName: config.require('keyName'),
        instanceType: config.require('instanceType'),
        spotPrice: maxPrice.toString(),
        userData: userData,
        securityGroups: [securityGroup.id],
        iamInstanceProfile: new aws.iam.InstanceProfile('minecraft-instance-profile', {
            role: role
        })
    })

    const capacity = config.getBoolean('on') || config.getBoolean('on') === undefined ? 1 : 0
    const asg = new aws.autoscaling.Group('minecraft-asg', {
        minSize: capacity,
        maxSize: capacity,
        desiredCapacity: capacity,
        launchConfiguration: launch,
        availabilityZones: availabilityZones,
        tags: [{
            key: 'project',
            value: 'minecraft',
            propagateAtLaunch: true
        }]
    })

    return {
        computeMaxCostPerMonth: maxPrice * 24 * 365 / 12,
        computeExpectedCostPerMonth: minPrice * 24 * 365 / 12,
        version,
        domain: record.name
        // things we want in the exports
    }
}

async function createMinecraftSecurityGroup() {
    const myIpRequest = await axios.default.get<String>('https://v4.ident.me')
    const my_ip: String = myIpRequest.data
    return new aws.ec2.SecurityGroup("minecraft-security-group", {
        description: "Allow traffic to the instance",
        ingress: [
            {
                description: "Connect to Mincraft port",
                fromPort: 25565,
                toPort: 25565,
                protocol: "tcp",
                cidrBlocks: ["0.0.0.0/0"]
            },
            {
                description: "Allow SSH from my IP only",
                fromPort: 22,
                toPort: 22,
                protocol: "tcp",
                cidrBlocks: [my_ip + "/32"]
            }
        ],
        egress: [
            {
                description: "Connect to internet",
                fromPort: 0,
                toPort: 0,
                protocol: "-1",
                cidrBlocks: ["0.0.0.0/0"]
            }
        ]
    })
}

interface SpotPrices {
    availabilityZones: string[],
    minPrice: number
}

async function determineSpotPricePerHour(): Promise<SpotPrices> {

    const azsResponse = await aws.getAvailabilityZones({ state: 'available' })
    const prices: number[] = []
    const names: string[] = []
    for (const name of azsResponse.names) {
        try {
            const returned = await aws.ec2.getSpotPrice({
                availabilityZone: name,
                instanceType: config.require('instanceType'),
                filters: [{
                    name: 'product-description',
                    values: ['Linux/UNIX']
                }]
            })
            prices.push(Number.parseFloat(returned.spotPrice))
            names.push(name)
        } catch (error) {
            console.log(`Error looking up spot price history for: ${name}, most likely this means there is not spot available there.`)
        }
    }
    const minPrice = Math.min(... prices);
    return {availabilityZones: names, minPrice};
}

async function determineServerVersion() {
    const version = config.get('version')
    if (version) {
        return {
            sha: version,
            version: version
        }
    }
    const response = await axios.default.get<String>('https://www.minecraft.net/en-us/download/server')
    const match = response.data.match(/"https:\/\/launcher\.mojang\.com\/v1\/objects\/(.*?)\/server.jar".*?\>minecraft_server\.(.*?)\.jar\</);
    if (match == null) {
        throw new Error("No matches were found, please fix the REGEX above.");
    }
    return {
        sha: match[1],
        version: match[2]
    }
}

interface User {
   name: string,
   id: string
}

async function lookupUser(userName: string): Promise<User> {
    const response = await axios.default.get<any>(`https://api.mojang.com/users/profiles/minecraft/${userName}`)
    return response.data
}

async function computeOperatorsFileString(): Promise<string> {
    const operators = []
    const opsConfig = config.requireObject<string[]>('operators')
    for (const userName of opsConfig) {
        const user = await lookupUser(userName)
        operators.push({
            "uuid": toUUID(user.id),
            "name": user.name,
            "level": 4,
            "bypassesPlayerLimit": false
        });
    }
    return JSON.stringify(operators);
}

async function computePlayersFileString(): Promise<string> {
    const players = []
    const opsConfig = config.requireObject<string[]>('players')
    for (const userName of opsConfig) {
        const user = await lookupUser(userName)
        players.push({
            "uuid": toUUID(user.id),
            "name": user.name
        });
    }
    return JSON.stringify(players);
}

async function createRoutingRecord() {
    const zoneId = config.require('zone');
    const domainZone = await aws.route53.getZone({ zoneId: zoneId})
    return new aws.route53.Record('minecraft', {
        zoneId: domainZone.zoneId,
        name: `minecraft.${domainZone.name}`,
        type: 'A',
        ttl: 5,
        records: ['1.2.3.4']
    })
}

const goPromise = go()

export const computeCostPerMonthExpected = goPromise.then(res => res.computeExpectedCostPerMonth)
export const computeCostPerMonthMax = goPromise.then(res => res.computeMaxCostPerMonth)
export const minecraftVersion = goPromise.then(res => res.version)
export const domain = goPromise.then(res => res.domain)
