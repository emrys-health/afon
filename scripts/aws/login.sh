echo "aws login"
aws login || exit 1

# Shows who is logged in, so that no other step is needed
source $AFON_ROOT/scripts/aws/account.sh
