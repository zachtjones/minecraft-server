# Minecraft Server
Infrastructure for provisioning a minecraft server

Using Pulumi + AWS


## Setup

**Note** This is currently written for Windows 10, instructions should be similar for Mac / Linux

1. Install node js (latest)
1. Install [aws - windows](https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2-windows.html)
1. Create a new IAM user (programmatic access only)
1. Configure your AWS environment to use those credentials `aws configure`
1. Install [Pulumi and configure AWS](https://www.pulumi.com/docs/get-started/aws/begin/)
1. Create a pulumi stack + fill in template (documented below)
1. Pulumi preview

## Config

```yaml
config:
  aws:region: us-east-2 # This should be set on creation of the stack.
  minecraft:zone: A00A00A0AA0A0A # AWS Route53 hosted zone (for DNS)
  minecraft:operators: # list of operators (admins of the server), can be empty
    - mumboJumbo
  minecraft:players: # list of players who are not operators
    - cubfan125
  minecraft:title: # String title that shows up to users that want to connect
  minecraft:javaArgs: '-Xms1g -Xmx4g' # based on the max memory available
  minecraft:keyName: 'minecraft_key_pair' # create a ec2 key pair, and then put the name here
  minecraft:instanceType: 'm6g.medium'
  minecraft:ami_filter: 'amzn2-ami-hvm-*-arm64-gp2'  # amazon 2 for the arm-based ec2s
  # for x86_64, use 'amzn2-ami-hvm-*-x86_64-gp2'
```

## Known Issues
1. Security: Instance can do anything with s3 - should be limited to read/write from current bucket + write to backup bucket. Anyone who can SSH to the host (which should be just you) has s3:* permissions.
2. Security: Instance can do anything with route53 - should be limited to upsert on the minecraft.domain.com -- requires SSH access to the host
