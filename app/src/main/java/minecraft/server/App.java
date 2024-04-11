package minecraft.server;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.ec2.SecurityGroup;
import com.pulumi.aws.ec2.SecurityGroupArgs;
import com.pulumi.aws.ec2.SecurityGroupRule;
import com.pulumi.aws.ec2.SecurityGroupRuleArgs;
import com.pulumi.aws.ecs.Cluster;
import com.pulumi.aws.ecs.ClusterArgs;
import com.pulumi.aws.ecs.TaskDefinition;
import com.pulumi.aws.ecs.TaskDefinitionArgs;
import com.pulumi.aws.ecs.inputs.ClusterSettingArgs;
import com.pulumi.aws.ecs.inputs.TaskDefinitionRuntimePlatformArgs;
import com.pulumi.aws.ecs.inputs.TaskDefinitionVolumeArgs;
import com.pulumi.aws.ecs.inputs.TaskDefinitionVolumeEfsVolumeConfigurationArgs;
import com.pulumi.aws.efs.FileSystem;
import com.pulumi.aws.efs.FileSystemArgs;
import com.pulumi.aws.efs.MountTarget;
import com.pulumi.aws.efs.MountTargetArgs;
import com.pulumi.aws.iam.Role;
import com.pulumi.aws.iam.RoleArgs;
import com.pulumi.aws.iam.RolePolicy;
import com.pulumi.aws.iam.RolePolicyArgs;
import com.pulumi.awsx.ec2.DefaultVpc;
import com.pulumi.core.Output;

import java.util.Map;

public class App {
    private static final String version = "24w13a";

    private static final int minecraftPort = 25565;
    private static final int networkFileSystemPort = 2049;


    public static void main(String[] args) {
        Pulumi.run(App::stack);
    }

    public static void stack(Context context) {
        // note: uses public subnets of the default VPC

        var inputTags = Map.of("project", "minecraftV2");


        // Fargate Cluster - serverless by default
        var cluster = new Cluster("cluster", ClusterArgs.builder()
            .name("Minecraft-Cluster")
            .settings(
                ClusterSettingArgs.builder()
                    .name("containerInsights")
                    .value("enabled")
                    .build()
            )
            .tags(inputTags)
            .build()
        );

        // deploy into the default VPC
        var vpc = new DefaultVpc("default-vpc");

        // EFS is where the world will be stored
        var fileSystem = new FileSystem("minecraft-filesystem", FileSystemArgs.builder()
            .throughputMode("elastic") // Elastic will be best for Minecraft servers
            .tags(inputTags)
            .build()
        );
        // TODO backup policy

        var securityGroupFileSystem = new SecurityGroup("minecraft-security-group-fs", SecurityGroupArgs.builder()
            .tags(inputTags)
            .build());

        var securityGroupContainer = new SecurityGroup("minecraft-security-group", SecurityGroupArgs.builder()
            .tags(inputTags)
            .build());

        // allow in from container to EFS
        new SecurityGroupRule("minecraft-security-group-rule-inbound-nfs-port", SecurityGroupRuleArgs.builder()
            .description("Allow minecraft inbound connections")
            .fromPort(networkFileSystemPort)
            .toPort(networkFileSystemPort)
            .sourceSecurityGroupId(securityGroupContainer.id())
            .protocol("all")
            .type("ingress")
            .securityGroupId(securityGroupFileSystem.id())
            .build());

        // Mount the EFS to the VPC
        vpc.publicSubnetIds().applyValue(subnets -> subnets.stream().map(subnet ->
            new MountTarget("minecraft-mountTarget-" + subnet, MountTargetArgs.builder()
                .fileSystemId(fileSystem.id())
                .subnetId(subnet)
                .securityGroups(Output.all(securityGroupFileSystem.id()))
                .build())
        ));


        var role = new Role("minecraft-role", RoleArgs.builder()
            .assumeRolePolicy("""
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Action": "sts:AssumeRole",
                    "Principal": {
                        "Service": "ecs-tasks.amazonaws.com"
                    },
                    "Effect": "Allow"
                  }]
                }
                """
            )
            .tags(inputTags)
            .build());

        // give our minecraft task the basic permissions required
        new RolePolicy("minecraft-role-policy", RolePolicyArgs.builder()
            .role(role.name())
            .policy(
                """
                {
                    "Version": "2012-10-17",
                    "Statement": [
                        {
                            "Effect": "Allow",
                            "Action": [
                                "ecr:GetAuthorizationToken",
                                "ecr:BatchCheckLayerAvailability",
                                "ecr:GetDownloadUrlForLayer",
                                "ecr:BatchGetImage",
                                "logs:CreateLogStream",
                                "logs:PutLogEvents"
                            ],
                            "Resource": "*"
                        }
                    ]
                }
                """
            )
            .build()
        );

        var taskDefinition = new TaskDefinition("minecraft-task", TaskDefinitionArgs.builder()
            // https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-properties-ecs-taskdefinition-containerdefinition.html
            // Version can also be LATEST or SNAPSHOT
            // Image documentation here: https://docker-minecraft-server.readthedocs.io/en/latest/
            // Default MEMORY on the container to "" to have it use the max ram percentage instead
            .containerDefinitions(
                """
                [
                    {
                        "name": "minecraft",
                        "environment": [
                            {"name": "EULA", "value": "TRUE"},
                            {"name": "VERSION", "value": "$VERSION"},
                            {"name": "MEMORY", "value": ""},
                            {"name": "JVM_XX_OPTS", "value": "-XX:MaxRAMPercentage=75"},
                            {"name": "CUSTOM_SERVER_PROPERTIES", "value": "initial-enabled-packs=update_1_21"}
                        ],
                        "essential": true,
                        "image": "itzg/minecraft-server:latest",
                        "stopTimeout": 60,
                        "mountPoints": [
                            {
                                "sourceVolume": "minecraft-volume",
                                "containerPath": "/data"
                            }
                        ]
                    }
                ]
                """.replace(
                    "$VERSION", version
                )
            )
            // Units are partial CPU (1024 is 1 core) and MB for memory
            .cpu("2048")
            .memory("4096")
            .family("LINUX")
            .networkMode("awsvpc") // required by Fargate scheduler
            .requiresCompatibilities("FARGATE")
            .runtimePlatform(TaskDefinitionRuntimePlatformArgs.builder()
                .cpuArchitecture("ARM64")
                .operatingSystemFamily("LINUX")
                .build())
            .taskRoleArn(role.arn())
            .volumes(TaskDefinitionVolumeArgs.builder()
                .name("minecraft-volume")
                .efsVolumeConfiguration(TaskDefinitionVolumeEfsVolumeConfigurationArgs.builder()
                    .fileSystemId(fileSystem.id())
                    .transitEncryption("ENABLED")
                    .build()
                )
                .build()
            )
            .tags(inputTags)
            .build());

        // allow in on the minecraft port
        new SecurityGroupRule("minecraft-security-group-rule-inbound-mc-port", SecurityGroupRuleArgs.builder()
            .description("Allow minecraft inbound connections")
            .fromPort(minecraftPort)
            .toPort(minecraftPort)
            .cidrBlocks("0.0.0.0/0")
            .protocol("tcp")
            .type("ingress")
            .securityGroupId(securityGroupContainer.id())
            .build());

        // allow out to the internet on all ports
        new SecurityGroupRule("minecraft-security-group-rule-outbound-internet", SecurityGroupRuleArgs.builder()
            .description("Allow server to reach out to the internet on all ports")
            .fromPort(0)
            .toPort(65535)
            .cidrBlocks("0.0.0.0/0")
            .protocol("all")
            .type("egress")
            .securityGroupId(securityGroupContainer.id())
            .build());


//        var service = new Service("minecraft-service", ServiceArgs.builder()
//            .cluster(cluster.arn())
//            .taskDefinition(taskDefinition.arn())
//            .launchType("FARGATE")
//            .propagateTags("SERVICE")
//            .tags(inputTags)
//            .networkConfiguration(ServiceNetworkConfigurationArgs.builder()
//                .subnets(vpc.publicSubnetIds())
//                .securityGroups(Output.all(securityGroupContainer.id()))
//                .build())
//            .desiredCount(containerCount)
//            .build());

        // Create an ECS service
        // -- launch FARGATE
        // -- platform latest
        // -- volume is the EFS mount

        // To associate Fargate and Fargate Spot capacity providers to a cluster, you must use the Amazon ECS API or AWS CLI. You cannot associate them using the console.

        /// You can associate a capacity provider with an existing cluster using the PutClusterCapacityProviders API operation.
        // Linux tasks with the ARM64 architecture don't support the Fargate Spot capacity provider.


        context.export("fileSystem", fileSystem.arn());
        context.export("task", taskDefinition.arn());
        context.export("securityGroupContainer", securityGroupContainer.id());
        context.export("Instructions", Output.of(
            """
            Instructions for starting the server:
            
            1. Open AWS Console
            2. ECS
            3. Task Definitions
            4. Click on Linux -> grab the latest
            5. Deploy -> Run task
            6. Only need to change networking
                * Security groups - add the security group from outputs
            7. Create - takes ~30 seconds
            8. When done, stop the container (ECS -> Clusters -> Tasks -> Stop)
            """
        ));
    }
}

