# Deploying the environment

In `afon-infro`, run:
```shell
mvn  compile -Dexec.mainClass=health.emrys.afon.demo.DeployFullStackAwsMain  exec:exec -Pdemo
```
Add tables to Polaris with something like:
```shell
mvn compile -Dexec.mainClass=health.emrys.afon.core.configuration.LocalPolarisRegisterMain -Dexec.args="AWS acceptancetests AwsAccessSpec s3://emrys-afon-bucket/acceptancetests/AwsAccessSpec251117511401/metadata/00000-685ec54b-684f-48cb-bb58-6ae6aacb92e6.metadata.json emryspolaris.click phil s3cr3t" exec:java
```
(This is just an old table that `afon-acceptancetests` created and the salient file in any table is the `*.metadata.json` one).

Then, login to `emrysjupyter.click` and follow the OAuth link to fire up your pod.
You should see it being created with `kubectl get pods`.
It may take a minute or two.

User names and passwords can be found in `Onboarding.java`.
`DeployFullStackAwsMain.java` should have configured these users for AWS but if you need them for other Catalogs, look at `UserOnboarderMain.java`.

Let's assume you want to put a notebook on, say, the `phil` pod. In the `notebooks` sub directory, run:
```shell
POD=$(kubectl get pods | grep phil | awk '{print $1}') # swap 'phil' for 'will' if you want his pod
kubectl cp ./CrossCloudJoinExample.ipynb $POD:/home/jovyan/CrossCloudJoinExample.ipynb
```
Give it a second or two and your notebook will appear in your Jupyter pod. 