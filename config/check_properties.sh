for EXAMPLE in $(ls *.example) ; do {
  export ENV_FILE=$(echo $EXAMPLE | perl -pe s/\\.[a-zA-Z0-9]*$//g )
  ls $ENV_FILE || echo "no file called $EXAMPLE"
  for KEY in $(grep = $EXAMPLE | grep -v ^\# | grep -e ^[A-Z] | perl -pe s/=.*//g) ; do {
    grep ^$KEY= $ENV_FILE > /dev/null || echo "no $KEY in $EXAMPLE" ;
  } done
  for KEY in $(grep \= $ENV_FILE | grep -v ^\# | grep -e ^[A-Z] | perl -pe s/=.*//g) ; do {
      grep ^$KEY= $EXAMPLE > /dev/null || echo "no $KEY in $ENV_FILE" ;
  } done
} done