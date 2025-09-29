eksctl delete cluster --name spark-cluster --region eu-west-2

# not sure this is actually linked....?
#aws iam detach-role-policy --role-name ReadSecrets --policy-arn  arn:aws:iam::123456789012:policy/SecretAccessPolicy
#aws iam delete-role --role-name ReadSecrets

aws iam detach-role-policy --role-name emrys-deployment-role --policy-arn arn:aws:iam::123456789012:role/emrys-deployment-role
aws iam delete-role --role-name emrys-deployment-role
