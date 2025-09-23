eksctl create cluster --name spark-cluster --nodes 3
eksctl utils associate-iam-oidc-provider \
  --cluster spark-cluster \
  --approve
eksctl update addon \
  --name vpc-cni \
  --cluster spark-cluster \
  --region eu-west-2
