VPCS=$(aws ec2 describe-vpcs --query "Vpcs[?Tags[?Key=='Name' && Value=='cdktf-eks-vpc-polaris']].VpcId" --output text)
if [ -n "$VPCS" ]; then
  echo "AWS Polaris running: $VPCS"
else
  echo "No AWS Polaris running"
fi
