CONFIG_FILES="afon.properties secret.properties"
if [ -n "$CLOUD" ] && [ "$CLOUD" != "ALL" ]; then
  echo "Config relative to cloud $CLOUD only"
  CONFIG_FILES="$CONFIG_FILES afon-$CLOUD.properties secret-$CLOUD.properties catalogs-$CLOUD.properties"
else
  echo "Config relative to all clouds"
  CONFIG_FILES="$CONFIG_FILES afon-aws.properties afon-azure.properties afon-gcp.properties"
  CONFIG_FILES="$CONFIG_FILES secret-aws.properties secret-azure.properties secret-gcp.properties"
  CONFIG_FILES="$CONFIG_FILES catalogs-aws.properties catalogs-azure.properties catalogs-gcp.properties"
fi
CONFIG_FILES="$CONFIG_FILES users.csv users-acl.csv"
