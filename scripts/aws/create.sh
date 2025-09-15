aws s3api create-bucket --bucket emrys-afon-bucket --region eu-west-2 --create-bucket-configuration LocationConstraint=eu-west-2
aws iam create-group --group-name AfonGroup
aws iam create-role --role-name S3ReadWriteRole --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
              "AWS": "arn:aws:iam::418295715997:root"
      } ,
      "Action": "sts:AssumeRole"
    }
  ]
}'
aws iam attach-role-policy   --role-name S3ReadWriteRole  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
# TODO - make this next line's arn dynamic
aws iam create-policy --policy-name AssumeS3ReadRolePolicy --policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "arn:aws:iam::418295715997:role/S3ReadWriteRole"
     }
  ]
}'
aws iam attach-group-policy --group-name AfonGroup --policy-arn arn:aws:iam::418295715997:policy/AssumeS3ReadRolePolicy
