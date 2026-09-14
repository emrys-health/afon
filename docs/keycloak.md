# KeyCloak

## Database Bootstrap

Checkout KeyCloak from Github.

Run:
```shell
mvn clean install -DskipTests -Dgpg.skip=true
```
Find the executible with:
```shell
find . -name kc.sh
```
For me, it was at:
```
./testsuite/integration-arquillian/servers/auth-server/quarkus/target/unpacked/keycloak-999.0.0-SNAPSHOT/bin/kc.sh
```
Edit `./quarkus/dist/src/main/content/conf/keycloak.conf` and put your properties in there.

Note that you will need to create the actual database in your RDBMS by hand.

Now you have everything so run:
```shell
./testsuite/integration-arquillian/servers/auth-server/quarkus/target/unpacked/keycloak-999.0.0-SNAPSHOT/bin/kc.sh -cf ./quarkus/dist/src/main/content/conf/keycloak.conf start-dev
```
and it will start a server locally but it will bootstrap the database.