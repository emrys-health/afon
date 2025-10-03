export REGION=eu-west-2
export CLUSTERNAME=spark-cluster

eksctl delete cluster --name $CLUSTERNAME --region $REGION

POLICY_ARN=$(aws iam list-policies --query "Policies[?PolicyName=='nginx-deployment-policy'].Arn" --output text | tr -d '[:cntrl:]' | perl -pe s/.*arn/arn/g | perl -pe s/\\[.*//g)
if [[ -v POLICY_ARN ]]; then
  aws iam detach-role-policy --role-name emrys-deployment-role --policy-arn $POLICY_ARN
fi

aws iam delete-role --role-name emrys-deployment-role

if [[ -v POLICY_ARN ]]; then
  aws iam delete-policy  --policy-arn $POLICY_ARN
fi

aws --region "$REGION" secretsmanager  delete-secret --secret-id CloudDetails --force-delete-without-recovery
aws --region "$REGION" secretsmanager  delete-secret --secret-id application_default_credentials.json --force-delete-without-recovery

