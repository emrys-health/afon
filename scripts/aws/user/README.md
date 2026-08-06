It appears that AWS does not have service accounts so create a user with limited privileges to be an approximation.

Create the user - we;ll call it `saemrys`:
```shell
$ aws iam create-user --user-name saemrys
{
    "User": {
        "Path": "/",
        "UserName": "saemrys",
        "UserId": "AIDAWCZC6DCOXZWWJG6T2",
        "Arn": "arn:aws:iam::123456789012:user/saemrys",
        "CreateDate": "2026-07-21T15:00:50+00:00"
    }
}
```

```shell
$ aws iam create-access-key --user-name saemrys
{
    "AccessKey": {
        "UserName": "saemrys",
        "AccessKeyId": "AWS_ACCESS_KEY_ID",
        "Status": "Active",
        "SecretAccessKey": "AWS_SECRET_ACCESS_KEY",
        "CreateDate": "2026-07-21T15:01:05+00:00"
    }
}
```
where I've redacted sensitive values that should become `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in your `secret.properties`. 

First, let's get all the properties into our shell:

```shell
set -a && source afon.properties && set +a
```

Then create a role:
```shell
aws iam create-role --role-name S3ReadWriteRoleSA --assume-role-policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    {
      \"Effect\": \"Allow\",
      \"Principal\": {
              \"AWS\": \"arn:aws:iam::$AWS_ACCOUNT_ID:root\"
      } ,
      \"Action\": \"sts:AssumeRole\"
    }
  ]
}" 
```
and add the Glue role for all the tests to pass:
```shell
aws iam put-user-policy \
    --user-name saemrys \
    --policy-name Allow-AssumeRole-GlueIcebergRole \
    --policy-document file://glue-iceberg-assume-role.json
```


Finally, give `saemrys` the right permissions. Note that `saemrys` is a string inside `trust-policy.json`.
```shell
aws iam attach-role-policy        --role-name S3ReadWriteRoleSA --policy-arn  arn:aws:iam::$AWS_ACCOUNT_ID:policy/AfonBucketWhiteList
aws iam update-assume-role-policy --role-name S3ReadWriteRoleSA --policy-document file://trust-policy.json
```
You may need to `unalias aws` if you're using the Dockerized `aws` executable.