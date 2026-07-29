## Install tools on runner

# Chocolatey

```ps1
Set-ExecutionPolicy Bypass -Scope Process -Force
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072
iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))
```

# Tools

```ps1
choco install temurin17 -y
choco install maven -y
choco install docker-desktop -y # not working on Windows Server
wsl --install -d Ubuntu # requires virtualisation on EC2
```