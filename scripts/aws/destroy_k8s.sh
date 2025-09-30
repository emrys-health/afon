export REGION=eu-west-2
export CLUSTERNAME=spark-cluster

eksctl delete cluster --name $CLUSTERNAME --region $REGION

POLICY_ARN=$(aws iam list-policies --query "Policies[?PolicyName=='nginx-deployment-policy'].Arn" --output text | tr -d '[:cntrl:]' | perl -pe s/.*arn/arn/g | perl -pe s/\\[.*//g)
aws iam detach-role-policy --role-name emrys-deployment-role --policy-arn $POLICY_ARN
#ROLE_ARN=$(aws iam get-role --role-name emrys-deployment-role --query "Role.Arn" --output text | tr -d '[:cntrl:]' | perl -pe s/.*arn/arn/g  | perl -pe s/\\[.*//g)
aws iam delete-role --role-name emrys-deployment-role
aws iam delete-policy  --policy-arn $POLICY_ARN

aws --region "$REGION" secretsmanager  delete-secret --secret-id CloudDetails --force-delete-without-recovery
aws --region "$REGION" secretsmanager  delete-secret --secret-id GoogleSecret --force-delete-without-recovery

