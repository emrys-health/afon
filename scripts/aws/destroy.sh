aws iam detach-role-policy --role-name S3ReadWriteRole --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
aws iam delete-role --role-name S3ReadWriteRole
#aws iam detach-group-policy --group-name AfonGroup --policy-arn arn:aws:iam::123456789012:policy/AssumeS3ReadRolePolicy
#aws iam delete-group --group-name AfonGroup
aws s3 rm s3://emrys-afon-bucket --recursive
aws s3api delete-bucket --bucket  emrys-afon-bucket

#POLICY_ARN=$(aws iam list-policies --scope Local --query "Policies[?PolicyName=='AssumeS3ReadRolePolicy'].Arn" --output text| tr -d '[:cntrl:]')
#POLICY_ARN=$(echo $POLICY_ARN | perl -pe s/.*arn/arn/g | perl -pe s/Policy.*/Policy/g)
#aws iam delete-policy --policy-arn "$POLICY_ARN"

