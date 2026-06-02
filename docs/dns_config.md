# Intro

Polaris needs all communication encrypted. 
To encrypt HTTP traffic, you need HTTPS.
To use HTTPS you need a domain name that is recognised by a Certificate Authority.

We use `Let's Encrypt` to create certificates for us but for Kubernetes to point a
domain name at a particular pod, you need to prove you own it. This doc should guide you.

# Instructions

Here we assume that you've bought your domain name in AWS and want other cloud providers to manage it.
If you've bought your domain name through other registries (eg, GoDaddy) the process is roughly the 
same. 

### Azure

Create a DNS zone for the combination of domain name and resource group. 
For example:
```shell
az network dns zone create  --resource-group $AZURE_RESOURCE_GROUP  --name $YOUR_DOMAIN
```
This will print out some JSON that contains nameservers, for example:
```json
{
  ...
  "nameServers": [
    "ns1-02.azure-dns.com.",
    "ns2-02.azure-dns.net.",
    "ns3-02.azure-dns.org.",
    "ns4-02.azure-dns.info."
  ],
  ...
}
```
Make a note of these.

### GCP

Create a DNS zone through the GUI. 
Note you might need admin rights to do this.
Once it's created, you can run:
```shell
$ gcloud dns managed-zones describe $YOUR_ZONE_NAME --project=$YOUR_PROJECT
```
Just like for Azure, this will create a list of name servers:
```
nameServers:
- ns-cloud-c1.googledomains.com.
- ns-cloud-c2.googledomains.com.
- ns-cloud-c3.googledomains.com.
- ns-cloud-c4.googledomains.com.
```
Again, make a note.

### AWS

For AWS, you do need to let the infra code know about your domain.
Get the Zone ID for your domain by running something like:
```shell
aws route53 list-hosted-zones-by-name --dns-name emrysjupyter2.click
```
And then update the `AWS_XXX_DNS_ZONE` values in `afon.properties` accordingly.

Note the `AWS_ZONE_ID` is for the ELB and the `AWS_XXX_DNS_ZONE` are for the DNS. 
Although they are lexically similar (thank AWS for that) they are unrelated concepts.

This should be sufficient for DNS but if you want to use HTTPS, you'll need to assign a certificate.
If this is all in AWS, go to [AWS Certificate Manager](https://eu-west-2.console.aws.amazon.com/acm/certificates/list?region=eu-west-2)
and `Request` a certificate for your domain. 
You then need to press the `Create Record in Route 53` button to prove you own it.
Note that it might take a few minutes to propagate.

### Domain name registration

Go to the DNS section of your name registry.
For AWS, it's [here](https://us-east-1.console.aws.amazon.com/route53/domains/home).

Click the domain you've bought and in the top right corner, you'll see a section called `Name servers`.
Click the `Actions` button and choose `Edit name servers`.

![image](pics/aws_nameservers.png)

Now add the list of nameservers your cloud provider gave you. 
They should propagate within about 10 minutes.

# Consequences

Kubernetes running in the cloud that owns the DNS servers can now ask them to map a domain name to onf of its pods.
