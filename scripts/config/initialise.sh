echo "Initialising config"
CONFIG_DIR=$AFON_HOME/config
mkdir -p $CONFIG_DIR
cp -p $CWD/templates/config/* $CONFIG_DIR
echo "Generated empty config files from templates, which need to completed before proceeding."

