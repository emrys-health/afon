aws s3api create-bucket --bucket emrys-afon-bucket --region eu-west-2 --create-bucket-configuration LocationConstraint=eu-west-2
aws iam create-group --group-name AfonGroup
aws iam create-role --role-name S3ReadWriteRole --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
              "AWS": "arn:aws:iam::123456789012:root"
      } ,
      "Action": "sts:AssumeRole"
    }
  ]
}'
aws iam attach-role-policy   --role-name S3ReadWriteRole  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
ROLE_ARN=$(aws iam get-role --role-name S3ReadWriteRole --query "Role.Arn" --output text  | tr -d '[:cntrl:]')
ROLE_ARN=$(echo $ROLE_ARN | perl -pe s/.*arn/arn/g | perl -pe s/Role.*/Role/g)
aws iam create-policy --policy-name AssumeS3ReadRolePolicy --policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "'$ROLE_ARN'"
     }
  ]
}'
aws iam attach-group-policy --group-name AfonGroup --policy-arn arn:aws:iam::123456789012:policy/AssumeS3ReadRolePolicy
