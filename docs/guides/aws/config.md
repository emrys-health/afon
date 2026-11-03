# AWS config and common setup

The config and setup steps common to the AWS env of a [TRE](tre.md) (stack `aws-full`) and of a [data provider](data.md)
(stack `aws-polaris`).  Do these first, then follow the doc of your side.

## 1. Pre-requisites

- An AWS account, `AWS_ACCOUNT_ID`, and a user with the permissions to create the env's elements (S3 buckets, IAM roles and policies, Route 53 zones,
  ACM certificates) and to deploy the stacks (EKS, VPC, load balancers); see [roles](../../../scripts/aws/roles.md).  There is no admin-only part, as on Azure: one user can do both.
- A registered domain name for each endpoint the stack serves (see [DNS configuration](../../dns_config.md)), eg `<env>polarisaws.click`, or a subdomain of
  one.  Its Route 53 hosted zone is created by `dns-create-zones aws`, after which the domain must be delegated to the Route 53 nameservers
  (done for you with `--route53` if the domain is registered with Route 53).
- A Linux or macOS machine.

## 2. Install the Afon CLI

Unzip the latest `afon-<version>-cli.zip` to `$HOME/.afon/cli` and add it to the path, in `~/.bash_profile`:
```shell
export AFON_CLI_DIR=$HOME/.afon/cli
PATH=$AFON_CLI_DIR:$PATH
export PATH
```
Check with `afon-cli.sh`, which lists the commands.

## 3. Install the local tools

```shell
afon-cli.sh local-setup -c aws
```
Installs git, jdk, maven, docker, opentofu, kubernetes-cli and the `awscli`.

## 4. Create and fill in the config

Choose the env name, eg `xyz`.  This creates `~/.afon/config-xyz` (and the dir for the `tofu` state):
```shell
afon-cli.sh config-init -e xyz -c aws
afon-cli.sh cdktf-init -e xyz -c aws
```
Set in `afon.properties`:
```shell
ENV_NAME=xyz
```
Set in `afon-aws.properties` (the properties common to every AWS stack):
```shell
AWS_ACCOUNT_ID=<account>
AWS_REGION=eu-west-2
AWS_S3_ROLE_ARN=arn:aws:iam::<account>:role/S3ReadWriteRole   # created by aws-create
AWS_GLUE_ROLE_ARN=<optional: only for the Glue catalog>
```
Set in `secret-aws.properties` the access keys of the user that Polaris and Gitea reach the buckets with (see the [user setup](../../../scripts/aws/user/README.md)):
```shell
AWS_ACCESS_KEY_ID=<key id>
AWS_SECRET_ACCESS_KEY=<secret>
```
Set the Polaris user in `secret.properties` (`USER_ID`).  The stack-specific properties are in the docs of each side.

Validate, then switch to the env, which just symlinks `~/.afon/config` to `~/.afon/config-xyz`
(never switch env while a command is running):
```shell
afon-cli.sh config-validate -c aws
afon-cli.sh config-switch -e xyz
```
Config can also be shared with `afon-cli.sh config-{import|export} <zip> -e xyz`.
See [configuration](../../configuration.md) for all the properties, and `catalogs-aws.properties` for the catalogs and their buckets.

## 5. User credentials

The scripts and the deployment need the access key of an IAM user: `tofu` and the AWS CLI take it from the default credentials chain of `~/.aws`.  A console login (`aws login`) alone is not enough: it does not write `~/.aws/credentials`, only a login session, which is not an access key.  So create the user and its key first, then check who you are.  The permissions the user needs are in [roles](../../../scripts/aws/roles.md).

### Create a user and its access key

Each deployer needs an IAM user of their own with an access key.  `aws-user` needs a user who can manage IAM, in the account `AWS_ACCOUNT_ID` (it refuses to run in another one), and is safe to repeat:
```shell
afon-cli.sh aws-user show [<username>]          # the user, its groups, policies and keys; the user logged in if none is given
afon-cli.sh aws-user create <username>          # the IAM user, in the group afon-deployers
afon-cli.sh aws-user create-key <username>      # an access key, stored in ~/.aws
afon-cli.sh aws-user delete-key <username>      # deletes it
```
- `create` adds the user to the group `afon-deployers`, which it creates with default policies `PowerUserAccess` and `IAMFullAccess` if absent.  The policies are broad: use
  `--policy-arn <arn>` to narrow to [roles](../../../scripts/aws/roles.md) outside a development account.
- `create-key` creates the access key and stores it, with `AWS_REGION`, in the profile of `~/.aws` given by `--profile` (default `default`), with `aws configure set`.  A profile that already has a key is not replaced: the key is only shown once.
  `--show-key` also prints the key, to hand it over to the user, securely: its secret can't be shown again.
- A user has up to two access keys.  `create-key` creates none if the user has one, so delete it first: `delete-key` deletes the only key, or the one given with `--key-id <id>`.  It does not change `~/.aws`, so remove the deleted key from `~/.aws/credentials`.

The access key is valid until deleted: give each person their own user, never share a key, and delete it when it is no longer needed.

### Check who you are

```shell
afon-cli.sh aws-login
```
Checks the current AWS credentials, which must be those of `AWS_ACCOUNT_ID`, and prints the ARN you are logged in as.  With the access key above it does no login:
`aws login`, which signs you in in the browser and keeps a short-lived session without writing `~/.aws/credentials`, only runs if the credentials found are not those of the account.  That session
is for the AWS CLI commands only.  `--force` logs in even if already logged in to `AWS_ACCOUNT_ID`, and `--profile <name>` logs in under that profile.  If `aws login` fails
with `Profile 'default' is already configured with Access Key credentials`, it won't overwrite static keys: use another profile, or remove them from `~/.aws/credentials`.
`afon-cli.sh aws-user show`, or `aws sts get-caller-identity`, also say who you are.

**NOTE:** The keys in `secret-aws.properties` (step 4) are not these: they are the ones Polaris and Gitea reach the buckets with inside the cluster.

## 6. Create the env

```shell
afon-cli.sh aws-create <stack-id>   # stack-id: aws-full|aws-keycloak|aws-polaris, or its role tre|identity|catalog
```
It creates the catalogs' S3 buckets, the `S3ReadWriteRole` role and the `AfonBucketWhiteList` policy on them (the forbidden catalog's bucket is created
but kept out of the whitelist, and explicitly denied).  The stack decides what else is created: for `aws-full`, which has Git, the Gitea bucket
`AWS_GITEA_BUCKET` and, if `AWS_POLARIS_LOG_BUCKET` is set, the bucket of Polaris' logs.  It is safe to repeat: what exists is skipped.
Set the role's ARN it creates as `AWS_S3_ROLE_ARN`.

## 7. DNS zones

`afon-cli.sh dns-create-zones aws <stack>` creates one Route 53 hosted zone per domain the stack serves.  Then delegate each domain to it, at the registrar or,
for a subdomain, with `afon-cli.sh dns-delegate aws <stack>`, or `--route53` for a domain registered with Route 53 (see [DNS configuration](../../dns_config.md)), and check:
```shell
afon-cli.sh aws-dns-zones         # the nameservers of each zone, and whether the registrar delegates to them
dig +short A <domain>
```

## 8. TLS certificate in ACM

Needs the zones of step 7 delegated, as ACM validates a domain by a CNAME it adds to its zone:
```shell
afon-cli.sh aws-issue-cert        # all|keycloak|jupyter|polaris: requests the ACM cert of each domain and waits for it to be issued
```
Nothing is configured with the result: the stacks look up the latest issued certificate of their domain, and its zone, when deployed.  A domain with an issued
certificate is skipped, unless `--force`.  ACM renews the certificates in use, as long as their validation record stays.

## 9. Useful checks and teardown

```shell
afon-cli.sh aws-cluster <stack|role>                # is the EKS cluster up?
afon-cli.sh aws-update-kube-config <stack|role>     # point kubectl at the cluster
afon-cli.sh aws-polaris                             # is Polaris serving?
afon-cli.sh aws-keycloak                            # is KeyCloak serving?
afon-cli.sh aws-destroy                             # deletes the IAM role and policy created by aws-create
```
