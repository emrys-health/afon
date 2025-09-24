eksctl create cluster --name spark-cluster --nodes 3
eksctl utils associate-iam-oidc-provider \
  --cluster spark-cluster \
  --approve
eksctl update addon \
  --name vpc-cni \
  --cluster spark-cluster \
  --region eu-west-2

helm repo add secrets-store-csi-driver https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
helm install -n kube-system csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver
kubectl apply -f https://raw.githubusercontent.com/aws/secrets-store-csi-driver-provider-aws/main/deployment/aws-provider-installer.yaml

# 1. create tehs secret
aws secretsmanager create-secret --name phsecret --secret-string phsecretvalue

# 2. create the IAM policy
aws iam create-policy   --policy-name SecretAccessPolicy   --policy-document '{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect":"Allow",
    "Action":["secretsmanager:GetSecretValue"],
    "Resource":["arn:aws:secretsmanager:eu-west-2:123456789012:secret:phsecret-7dLRuU*"]
  }]
}'

# output from this is the ID in teh Federated ARN below
aws eks describe-cluster --name spark-cluster --region eu-west-2 --query "cluster.identity.oidc.issuer" --output text | awk -F'/' '{print $NF}'

# 3. Create the IAM roles
aws iam create-role --role-name ReadSecrets --assume-role-policy-document '{
  "Version":"2012-10-17",
  "Statement":[
    {
      "Effect":"Allow",
      "Principal":{
        "Federated":"arn:aws:iam::123456789012:oidc-provider/oidc.eks.eu-west-2.amazonaws.com/id/3AC3F4D313370C6CF8DB8B334A27DC46"
      },
      "Action":"sts:AssumeRoleWithWebIdentity",
      "Condition":{
        "StringEquals":{
          "oidc.eks.eu-west-2.amazonaws.com/id/3AC3F4D313370C6CF8DB8B334A27DC46:sub":"system:serviceaccount:default:spark"
        }
      }
    }
  ]
}'

kubectl annotate serviceaccount spark eks.amazonaws.com/role-arn=arn:aws:iam::123456789012:role/ReadSecrets

kubectl apply -f secret_provider_class.yaml

# 1. check the secret
aws secretsmanager describe-secret --secret-id arn:aws:secretsmanager:eu-west-2:123456789012:secret:phsecret-7dLRuU --region eu-west-2
# 2. check the policies
aws iam list-policies --scope Local --query "Policies[].{Name:PolicyName, Arn:Arn}" --output table
# 3. check the roles
aws iam list-roles --query "Roles[?contains(RoleName, 'ecret')].{Name:RoleName, Arn:Arn}" --output table

# 5 service account
kubectl get serviceaccounts spark -o yaml

