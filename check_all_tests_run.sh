echo "Checking all tests contribute to scenarios.txt:"
for FILE in $(find afon-acceptance -name \*Spec.scala 2>/dev/null | perl -pe s/.*\\///g | perl -pe s/.scala//g | grep -v Abstract) ; do {
  grep $FILE ./afon-acceptance/target/surefire-reports/scenarios.txt >/dev/null || echo "no mention of $FILE"
} done