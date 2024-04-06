# Minecraft Server

Infrastructure for provisioning a minecraft server

Using Pulumi, a project similar to terraform but better + AWS

Pulumi is free to use for individual use.

## Setup

* Create a new IAM user (programmatic access only)
* Configure your AWS environment to use those credentials 
   * `aws configure` and follow the prompts
* Install Pulumi
   * `brew install pulumi/tap/pulumi`

* Create the stack configuration
  * `pulumi config env init`


## Deploy

Preview created resources:
`pulumi preview`

Deploy: (will prompt for review, will need to select `yes`)
`pulumi up`