# Operations

## Technical requirements 

To use Afon, you'll to satisfy the following technical requirements.

### Cloud provider

A cloud provider is required. The supported providers are: AWS, Azure and GCP.

We assume that the roles and permissions to create a kubernetes cluster have been set up.

### Polaris Database

An external database is required to store the Polaris metadata.

Credentials to access the database are specified in [configuration](configuration.md)

## Using the stack

We assume the stack is deployed (using the deployment [stacks](stacks.md) documentation).

### Logging in (multi-factor authentication)

Logging in to Jupyter or Guacamole goes through KeyCloak, which requires a second factor: a
time-based one-time code from an authenticator app (FreeOTP, Google Authenticator, Microsoft
Authenticator, ...).

The first time a user signs in, KeyCloak shows a "Mobile Authenticator Setup" page after their
username and password: they scan the barcode with their app and type the six digits it shows.
Every subsequent sign-in asks only for the six digits.

This is provisioned by `KeyCloakProvider.configureMfa`, which the user-onboarding step runs against
the `polaris` realm. It binds the realm to two copies of KeyCloak's built-in flows:

| Flow                  | Bound to              | Effect                                            |
|-----------------------|-----------------------|---------------------------------------------------|
| `browserFlow`         | `browser-mfa`         | a one-time code is always required                |
| `directGrantFlow`     | `direct-grant-no-otp` | no one-time code is asked for                     |

The second is what keeps Spark, Trino and the CLI working. They obtain tokens with a
username/password (a "direct grant"), and KeyCloak's stock direct-grant flow demands a one-time code
from any user who has enrolled one - which, with MFA on, is everybody. A batch job has nobody to read
an authenticator app, so the one-time code is required of interactive logins only; a direct grant is
protected by its confidential client's secret instead.

If a user loses their authenticator app, delete their OTP credential in the KeyCloak admin console
(Users -> the user -> Credentials) and they will be walked through setup again at their next login.

### Viewing Jupyter via Guacamole

You can view Jupyter via Guacamole via `https://[JUPYTER_DOMAIN_NAME]/guacamole`, using whichever 
Jupyter domain you're configured in the [configuration](configuration.md).

Note that we use Apache Guacamole to stream pixels, and X11 VNC to render the images on the server side
(see `start-vnc.sh`). That is, Guacamole runs in its own Kubernetes pod and streams the X11 data from the Jupyter pod.
