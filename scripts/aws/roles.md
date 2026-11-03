# AWS roles

The IAM permissions required on the AWS account configured as `AWS_ACCOUNT_ID` in `afon-aws.properties`.

There are two kinds of user: the one that **deploys the stacks**, and the one that **sets up the account**, ie runs
the scripts in this dir.  They can be the same person, but need different permissions.  Unlike Azure, there is no
admin-only part: one user can do both.

AWS has no counterpart of the predefined roles of GCP, so what is needed is given as IAM actions, by service, to put in a
policy of your own.  Where an AWS managed policy covers it, it is named, but it is broader than needed.

## Stack deployer

The identity running `afon-cli.sh manage-stack ... aws-polaris`, ie the one the AWS CLI and the AWS provider of `tofu`
resolve (see [Authentication](#authentication)), in the account `AWS_ACCOUNT_ID` and the region `AWS_REGION`.  It needs:

| Service         | Actions (or managed policy)                                              | Needed for                                                                                                                                  |
|-----------------|--------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| EC2 / VPC       | `ec2:*` on VPCs, subnets, route tables, internet and NAT gateways, Elastic IPs, security groups | The VPC of the cluster, its subnets, NAT and the egress Elastic IP.  The destroy also deletes the security groups the load balancers leave     |
| EKS             | `eks:*` (clusters, node groups, add-ons, access entries, access policy associations) | Creating, updating and deleting the EKS cluster, its node group and add-ons, and the cluster access entries                                 |
| IAM             | `iam:CreateRole`, `DeleteRole`, `GetRole`, `PassRole`, `AttachRolePolicy`, `DetachRolePolicy`, `PutRolePolicy`, `DeleteRolePolicy`, `CreateOpenIDConnectProvider`, `DeleteOpenIDConnectProvider`, `GetPolicy`, `ListAttachedRolePolicies`, `ListInstanceProfilesForRole`, `RemoveRoleFromInstanceProfile` | The roles of the cluster, of its nodes, of the EBS CSI driver, of the load balancer controller and of the snapshots (see [roles the stacks create](#roles-the-stacks-create)), and the cluster's OIDC provider |
| Elastic Load Balancing | `elasticloadbalancing:Describe*`                                 | Looking up the load balancer of the ingress, for the Route 53 record                                                                        |
| Route 53        | `route53:ListHostedZonesByName`, `GetHostedZone`, `ListResourceRecordSets`, `ChangeResourceRecordSets` | Reading the zone of each domain and creating its record sets                                                                                |
| ACM             | `acm:ListCertificates`, `DescribeCertificate`                            | Looking up the issued certificate of each domain                                                                                            |
| EBS / DLM       | `ec2:CreateVolume`, `DeleteVolume`, `DescribeVolumes`, `CreateTags`, `dlm:*`  | Only for a stack with Git, ie `aws-full`: the EBS volume of Gitea's repositories, which outlives the stack, and the snapshot policy        |
| STS             | `sts:GetCallerIdentity`                                                  | Who is logged in                                                                                                                            |

The `PowerUserAccess` managed policy covers all of these but IAM, so it is enough with `IAMFullAccess` added, but is broader than needed:
use it for a development account only.

The cluster is created with the deployer as its admin (`bootstrapClusterCreatorAdminPermissions`), and nobody else is until
they are given an EKS access entry, see [Access to the cluster](#access-to-the-cluster).

### Prerequisites

These are not permissions, but are also required:

- The IAM policy `AWSLoadBalancerControllerIAMPolicyCurl` exists in the account.  The stack looks it up, it does not create it: it is the
  policy the AWS Load Balancer Controller documents, ie `iam_policy.json` of the controller's release.
- The Route 53 hosted zone of each domain the stack serves exists (`afon-cli.sh dns-create-zones aws <stack>`) and is delegated, and an ACM
  certificate for each is issued (`afon-cli.sh aws-issue-cert`), see [DNS configuration](../../docs/dns_config.md).
- For a stack with Git, `AWS_GITEA_BUCKET` exists, made by `create.sh`.

### Authentication

The stacks and the scripts use the default credential chain of the AWS SDK, which the AWS CLI shares, and need the access key of an IAM user in it: the environment
variables `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, else the profile `AWS_PROFILE` (`default` when unset) of `~/.aws`, else the instance profile of the machine.
A console login (`aws login`) alone is not enough: it does not write `~/.aws/credentials`, only a login session, which is not an access key.  No token is written into the plan.

Create the user and its key with `afon-cli.sh aws-user` (see [New deployer](#new-deployer)), and check who you are with `afon-cli.sh aws-login`, which only
runs `aws login` if the credentials found are not those of `AWS_ACCOUNT_ID`.  `aws login` won't replace a profile that has access keys.

**NOTE:** The keys in `secret-aws.properties` are not these.  They are those of the user Polaris and Gitea reach the buckets with
inside the cluster, see [Polaris user](#polaris-user).

### Access to the cluster

Only the identity that created the cluster, and the principals given an EKS access entry, can use it with `kubectl`.  Another user
gets `error: You must be logged in to the server (Unauthorized)`.  Whoever has access gives it with, on the cluster `<cluster>`:

```shell
aws eks create-access-entry --cluster-name <cluster> --region $AWS_REGION --principal-arn arn:aws:iam::$AWS_ACCOUNT_ID:user/<account>
aws eks associate-access-policy --cluster-name <cluster> --region $AWS_REGION --principal-arn arn:aws:iam::$AWS_ACCOUNT_ID:user/<account> \
  --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy --access-scope type=cluster
```
which needs `eks:CreateAccessEntry` and `eks:AssociateAccessPolicy`.  The entry goes with the cluster.

### Roles the stacks create

The deployer creates these, which is why it needs the IAM actions above, with the AWS managed policies they carry:

| Role                                    | Managed policy                                                  | For                                       |
|-----------------------------------------|-----------------------------------------------------------------|-------------------------------------------|
| cluster role                            | `AmazonEKSClusterPolicy`                                        | The EKS control plane                     |
| node group role                         | `AmazonEKSWorkerNodePolicy`, `AmazonEKS_CNI_Policy`, `AmazonEC2ContainerRegistryReadOnly` | The worker nodes                |
| EBS CSI driver role                     | `AmazonEBSCSIDriverPolicy`                                      | The volumes of the pods, eg Gitea's       |
| `AmazonEKSLoadBalancerControllerRole<cluster>` | `AWSLoadBalancerControllerIAMPolicyCurl` (see above)     | The load balancer controller, which makes the ALB of the ingress |
| snapshot role (stacks with Git)         | `AWSDataLifecycleManagerServiceRole`                            | Snapshots of Gitea's volume               |

### Symptoms of a missing permission

| Error                                                                                      | Missing                                                    |
|--------------------------------------------------------------------------------------------|------------------------------------------------------------|
| `error: You must be logged in to the server (Unauthorized)`                                | An EKS access entry, see [Access to the cluster](#access-to-the-cluster) |
| `AccessDeniedException` ... `eks:DescribeCluster` from `aws-cluster`                       | `eks:DescribeCluster`                                      |
| `not authorized to perform: iam:CreateRole` / `iam:PassRole`                               | The IAM actions above                                      |
| `no matching IAM Policy found` for `AWSLoadBalancerControllerIAMPolicyCurl`                | Create the policy, see [Prerequisites](#prerequisites)     |
| `no matching Route 53 Hosted Zone found`, or a `route53:ChangeResourceRecordSets` denial    | The zone is missing or not delegated, or the Route 53 actions |
| `no certificate found` for a domain                                                        | `afon-cli.sh aws-issue-cert`, or `acm:ListCertificates`    |
| `Profile 'default' is already configured with Access Key credentials` from `aws-login`     | See [Authentication](#authentication)                      |

## Account setup

The user running `create.sh`, `destroy.sh`, `create_federated_permissions.sh`, `egress-ip.sh` and the `user` setup, all of
which use `AWS_ACCOUNT_ID` and `AWS_REGION`, also needs to create buckets and roles:

| Service | Actions                                                                                                                                       | Needed for                                                                                   |
|---------|-----------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| S3      | `s3:CreateBucket`, `ListBucket`, `DeleteBucket`, `DeleteObject`                                                                               | The catalogs' buckets, and the Gitea and Polaris log buckets; `destroy.sh` empties and deletes them |
| IAM     | `iam:CreateRole`, `GetRole`, `DeleteRole`, `CreatePolicy`, `GetPolicy`, `DeletePolicy`, `AttachRolePolicy`, `DetachRolePolicy`, `ListAttachedRolePolicies`, `UpdateAssumeRolePolicy`, `PutRolePolicy`, and, for the [Polaris user](#polaris-user), `CreateUser`, `CreateAccessKey`, `PutUserPolicy` | `S3ReadWriteRole`, its policy `AfonBucketWhiteList`, `GlueIcebergRole` and the Polaris user |
| EC2     | `ec2:AllocateAddress`, `DescribeAddresses`, `DescribeVpcs`                                                                                    | `egress-ip.sh`, which allocates the Elastic IP the cluster egresses from, and `vpcs.sh`      |

`S3ReadWriteRole` is what Polaris assumes to reach the buckets: its policy `AfonBucketWhiteList` allows the catalogs' buckets
whose `AWS_ADMIN_GRANTS_<id>` is `true`, and denies the forbidden one explicitly.  `destroy.sh` removes the role and its policy,
but not the buckets.

### New deployer

`afon-cli.sh aws-user [show|create|configure] [<username>]` sets up the credentials of a stack deployer.  It runs as a user who can
manage IAM, in the account `AWS_ACCOUNT_ID`, and is safe to repeat:

| Action      | Does                                                                                                                                                                       |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `show`      | The default.  Shows the user, its groups, policies and access keys, and whether the profile has a key, and changes nothing.  Without `<username>`, shows the user logged in |
| `create`    | Creates the IAM user, and adds it to the group `afon-deployers`, created with `PowerUserAccess` and `IAMFullAccess`, or the `--policy-arn` given, which should be narrowed to the [stack deployer](#stack-deployer) list outside a development account |
| `create-key` | Creates the access key of the user, and stores it in the profile `--profile` (default `default`) of `~/.aws`, with `aws configure set`, unless that profile has a key already, in which case the key is only shown.  `--show-key` also prints it, once, to hand it over |
| `delete-key` | Deletes the access key of the user, which must be its only one, else the one given with `--key-id`.  The profile in `~/.aws` that holds it is not changed |

### Polaris user

AWS has no service accounts, so Polaris and Gitea reach the buckets as an IAM user of limited permissions, `saemrys` in
[user/README.md](user/README.md): it may only assume `S3ReadWriteRoleSA` (`trust-policy.json`) and, for the Glue catalog,
`GlueIcebergRole` (`glue-iceberg-assume-role.json`).  Its access key is `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in
`secret-aws.properties`.  The secret is shown only once, when the key is created.

## TLS cert management

The user running `issue-cert.sh`, to request the ACM certificate of each domain and validate it by DNS:

| Service | Actions                                                                                   | Needed for                                                       |
|---------|-------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| ACM     | `acm:RequestCertificate`, `DescribeCertificate`, `ListCertificates`                       | Requesting the certificate and waiting for it to be issued       |
| Route 53 | `route53:ListHostedZonesByName`, `ChangeResourceRecordSets`                              | Adding the validation CNAME to the zone named after the domain   |

The stack deployer only needs `acm:ListCertificates` and `acm:DescribeCertificate` (see above) to look the certificate up.

## Note

This list is derived from the resources the stacks manage, as `AwsCloudProvider` defines them, from the calls the scripts
in this dir make, and from the failures seen deploying them.  It has not been verified from a clean account, so add any permission
a deployment is found to need.
