# Install Github Runner on Windows
https://github.com/emrys-health/afon-core/settings/actions/runners/new

## Install
```ps1
# Create a folder under the drive root
$ mkdir actions-runner; cd actions-runner
# Download the latest runner package
$ Invoke-WebRequest -Uri https://github.com/actions/runner/releases/download/v2.336.0/actions-runner-win-x64-2.336.0.zip -OutFile actions-runner-win-x64-2.336.0.zip
# Optional: Validate the hash
$ if((Get-FileHash -Path actions-runner-win-x64-2.336.0.zip -Algorithm SHA256).Hash.ToUpper() -ne 'd59123a43003e357b0805b5d0f611d0bd2f65ab67d51bd070dd4e7a0f685c162'.ToUpper()){ throw 'Computed checksum did not match' }
# Extract the installer
$ Add-Type -AssemblyName System.IO.Compression.FileSystem ; [System.IO.Compression.ZipFile]::ExtractToDirectory("$PWD/actions-runner-win-x64-2.336.0.zip", "$PWD")
```

## Configure
```ps1
# Create the runner and start the configuration experience
$ ./config.cmd --url https://github.com/emrys-health/afon-core --token AAB5PTIU3UAGYI4263I3S7LKNCS3C
# Run it!
$ ./run.cmd
```

# Use this YAML in your workflow file for each job
runs-on: self-hosted
