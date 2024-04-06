package minecraft.server;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.ec2.SecurityGroup;
import com.pulumi.aws.ec2.SecurityGroupArgs;
import com.pulumi.aws.ec2.SecurityGroupRule;
import com.pulumi.aws.ec2.SecurityGroupRuleArgs;
import com.pulumi.aws.ecs.*;
import com.pulumi.aws.ecs.inputs.*;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class App {

    public static final int minecraftPort = 25565;

    public static void main(String[] args) {
        Pulumi.run(App::stack);
    }

    public static void stack(Context context) {
        // set to 0 to turn off
        // note: uses public subnets of the default VPC
        var containerCount = 1;

        var inputTags = Map.of("project", "minecraftV2");


        // Fargate Cluster - serverless by default
        var cluster = new Cluster("cluster", ClusterArgs.builder()
            .name("minecraft")
//            .settings(
//                ClusterSettingArgs.builder()
//                    .name("containerInsights")
//                    .value("enabled")
//                    .build()
//            )
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

        // Mount the EFS to the VPC
        context.export("Mount Points",
            vpc.publicSubnetIds().applyValue(subnets -> subnets.stream().map(subnet ->
                new MountTarget("minecraft-mountTarget-" + subnet, MountTargetArgs.builder()
                    .fileSystemId(fileSystem.id())
                    .subnetId(subnet)
                    .build())
                ).collect(Collectors.toList()))
        );


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
            .containerDefinitions(
                """
                [
                    {
                        "name": "minecraft",
                        "environment": [
                            {"name": "EULA", "value": "TRUE"},
                            {"name": "VERSION", "value": "24w13a"},
                            {"name": "JVM_XX_OPTS", "value": "-XX:MaxRAMPercentage=75"}
                        ],
                        "essential": true,
                        "image": "itzg/minecraft-server:latest",
                        "stopTimeout": 60
                    }
                ]
                """.stripIndent()
            )
            // Units are mCPU and MB for memory
            .cpu("1024")
            .memory("2048")
            .family("LINUX")
            .networkMode("awsvpc") // required by Fargate scheduler
            .requiresCompatibilities("FARGATE")
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

        var securityGroup = new SecurityGroup("minecraft-security-group", SecurityGroupArgs.builder()
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
            .securityGroupId(securityGroup.id())
            .build());

        // allow out to the internet on all ports
        new SecurityGroupRule("minecraft-security-group-rule-outbound-internet", SecurityGroupRuleArgs.builder()
            .description("Allow server to reach out to the internet on all ports")
            .fromPort(0)
            .toPort(65535)
            .cidrBlocks("0.0.0.0/0")
            .protocol("all")
            .type("egress")
            .securityGroupId(securityGroup.id())
            .build());


        var service = new Service("minecraft-service", ServiceArgs.builder()
            .cluster(cluster.arn())
            .taskDefinition(taskDefinition.arn())
            .launchType("FARGATE")
            .propagateTags("SERVICE")
            .tags(inputTags)
            .networkConfiguration(ServiceNetworkConfigurationArgs.builder()
                .subnets(vpc.publicSubnetIds())
                .securityGroups(Output.all(securityGroup.id()))
                .build())
            .desiredCount(containerCount)
            .build());

        // Create an ECS service
        // -- launch FARGATE
        // -- platform latest
        // -- volume is the EFS mount

        // To associate Fargate and Fargate Spot capacity providers to a cluster, you must use the Amazon ECS API or AWS CLI. You cannot associate them using the console.

        /// You can associate a capacity provider with an existing cluster using the PutClusterCapacityProviders API operation.
        // Linux tasks with the ARM64 architecture don't support the Fargate Spot capacity provider.


        context.export("cluster", cluster.arn());
        context.export("fileSystem", fileSystem.arn());
        context.export("service", service.id());
    }
}

/*
Where we are right now;

The task is having issues pulling the image, my guess is that it's not able to make the outbound network connections

We may need to adjust the security group / add one to the task.



 */
