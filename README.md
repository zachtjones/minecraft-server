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

TODO


## Known Issues
1. Security: Instance can do anything with s3 - should be limited to read/write from current bucket + write to backup bucket
2. Security: Instance can do anything with route53 - should be limited to upsert on the minecraft.domain.com
