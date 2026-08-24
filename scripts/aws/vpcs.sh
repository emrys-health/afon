VPCS=$(aws ec2 describe-vpcs --query "Vpcs[?Tags[?Key=='Name' && Value=='cdktf-eks-vpc-spark-cluster']].VpcId" --output text)
if [ -n "$VPCS" ]; then
  echo "VPCS running: $VPCS"
else
  echo "No VPCS running"
fi
