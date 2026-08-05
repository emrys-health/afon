### Running Trino locally

You can run the server with Docker in the directory above this with:

```shell
docker run    --name trino   --network=host   -v ./config.properties:/etc/trino/config.properties   -v ./trino/catalog:/etc/trino/catalog   -e TRINO_JVM_OPTS="-Xmx4G"   trinodb/trino:latest 2>&1  | tee /tmp/trino3.log
```
then run the client with:
```shell
docker exec -it $(docker ps | grep trino | awk '{print $1}') trino --server http://localhost:8080 --debug
```
Run `select version();` and check that it's `481`.

Then, if you deployed a full stack, a test table is created and you can see it with:

```sql
SELECT * FROM aws.namespaceph.test_table
```
Or you can use another table if you populate the Polaris instance yourself.