# olympus
Maintenance server for Lightning Wallet

### Installation manual for Ubuntu 16.04 && 18.04

1. Install Java by following steps described at https://www.digitalocean.com/community/tutorials/how-to-install-java-with-apt-get-on-ubuntu-16-04 for Ubuntu 16.04:
```
sudo add-apt-repository ppa:openjdk-r/ppa
sudo apt-get update  
sudo apt install openjdk-11-jre
```

For Ubuntu 18.04:
```
sudo apt-get update  
sudo apt install openjdk-11-jre
```

2. Install Groestlcoin Core by following steps described at https://groestlcoin.org/forum/index.php?topic=299.0

3. Groestlcoin config file should contain the following lines: 
```
daemon=1
server=1

rpcuser=yourusernamehere # set your own
rpcpassword=yourpasswordhere # set your own

rpcport=1441
port=1331
txindex=1

addresstype=bech32

zmqpubrawtx=tcp://127.0.0.1:29000
zmqpubhashblock=tcp://127.0.0.1:29000
zmqpubrawblock=tcp://127.0.0.1:29000
```

4. Install and run a MongoDB by following steps described at https://docs.mongodb.com/manual/tutorial/install-mongodb-on-ubuntu/ for Ubuntu 16.04:
```
wget -qO - https://www.mongodb.org/static/pgp/server-4.2.asc | sudo apt-key add -
echo "deb [ arch=amd64 ] https://repo.mongodb.org/apt/ubuntu xenial/mongodb-org/4.2 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-4.2.list
sudo apt-get update
sudo apt-get install -y mongodb-org
sudo service mongod start
```

For Ubuntu 18.04:
```
wget -qO - https://www.mongodb.org/static/pgp/server-4.2.asc | sudo apt-key add -
echo "deb [ arch=amd64 ] https://repo.mongodb.org/apt/ubuntu bionic/mongodb-org/4.2 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-4.2.list
sudo apt-get update
sudo apt-get install -y mongodb-org
sudo service mongod start
```

5. Open MongoDB console and issue the following commands:
```
$ mongo

> use grs-olympus
> db.spentTxs.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 90 } )
> db.spentTxs.createIndex( { "prefix": 1 }, { unique: true } )
> db.spentTxs.createIndex( { "txids": 1 } )

> db.scheduledTxs.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 30 } )
> db.scheduledTxs.createIndex( { "cltv": 1 } )

> db.userData.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 365 * 4 } )
> db.userData.createIndex( { "key": 1 } )

> db.chanInfo.createIndex( { "shortChanId": 1 } )
> db.chanInfo.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 365 } )

> use grs-blindSignatures
> db.blindTokens.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 365 } )
> db.blindTokens.createIndex( { "seskey": 1 }, { unique: true } )
> var decimalAlphabet = "0123456789".split('')
> decimalAlphabet.forEach(function(v) { db["clearTokens" + v].createIndex( { "token": 1 }, { unique: true } ) })

> use grs-watchedTxs
> var hexAlphabet = "0123456789abcdef".split('')
> hexAlphabet.forEach(function(v) { db["watchedTxs" + v].createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 360 * 2 } ) })
> hexAlphabet.forEach(function(v) { db["watchedTxs" + v].createIndex( { "halfTxId": 1 } ) })

> quit()
```

6. Get Groestlcoin Eclair fat JAR file, either by downloading it directly from a repository or by compiling from source:  
```
wget https://github.com/Groestlcoin/eclair/releases/download/v0.3.2/eclair-node-0.3.2-SNAPSHOT-64c76f6.jar  
```

7. Create an `eclairdata` directory and put an `eclair.conf` file there with the following lines:
```
mkdir eclairdata && cd eclairdata
nano eclair.conf
```

```
eclair {
	chain = "mainnet" // "regtest" for regtest, "testnet" for testnet, "mainnet" for mainnet

	server {
		public-ips = ["82.196.11.189"] // external ips, will be announced on the network
		binding-ip = "0.0.0.0"
		port = 9196 // default = 9735
	}

	api {
		enabled = true // disabled by default for security reasons
		binding-ip = "82.196.11.189"
		port = 8089 // default = 8080
		password = "pass" // password for basic auth, must be non empty if json-rpc api is enabled
	}

        watcher-type = "bitcoind" // other *experimental* values include "electrum"
	
	bitcoind {
		host = "localhost"
		rpcport = 1441
		rpcuser = "yourusernamehere"
		rpcpassword = "yourpasswordhere"
		zmqblock = "tcp://127.0.0.1:29000"
		zmqtx = "tcp://127.0.0.1:29000"
	}
}

```

8. Run Groestlcoin Eclair instance by issuing: 
```
cd\
screen
java -Declair.datadir=eclairdata/ -jar eclair-node-0.3.2-SNAPSHOT-64c76f6.jar
```
9. Get Olympus fat JAR file by by downloading it directly from our repository: 
```
wget https://github.com/Groestlcoin/olympus/releases/download/v1.0/olympus-assembly-1.0.jar
```
Or build with with `sbt "set test in assembly := {}" clean assembly`

10. Create self signed cert
```
keytool -genkey -keyalg RSA -alias olympus1.groestlcoin.org -keystore keystore.jks -storepass pass123 -validity 1825 -keysize 2048
```

11. Run Olympus instance by issuing:
```
$ java -jar olympus-assembly-1.0.jar production "{
\"zmqApi\":\"tcp://127.0.0.1:29000\", // Groestlcoin ZeroMQ endpoint
\"ip\":\"82.196.11.189\", // Olympus API will be accessible at this address...
\"port\":9203, // ...and this port
\"privKey\":\"17237641984433455757821928886025053286790003625266087739786982589470995742521\", // To blind-sign storage tokens
\"btcApi\":\"yourusernamehere:yourpasswordhere@127.0.0.1:1441\", // Groestlcoin Json-RPC endpoint
\"eclairSockPort\":9196, // Eclair port
\"rewindRange\":144, // How many blocks to inspect on restart if Olympus was offline for some time, important for watchtower
\"eclairSockIp\":\"82.196.11.189\", // Eclair address
\"eclairNodeId\":\"0226cbef3bef64405046de9fb182acb0fd344e535b524c6e98d2a9131235b8390b\", // NodeId which can be found in eclair.log
\"paymentProvider\":{\"quantity\":50,\"priceMsat\":1000000,\"url\":\"http://82.196.11.189:8089\",\"description\":\"50 storage tokens for backup Olympus server at olympus1.groestlcoin.org\",\"tag\":\"EclairProvider\",\"pass\":\"pass\"},
\"sslFile\":\"keystore.jks\",
\"sslPass\":\"pass123\",
\"minCapacity\":10000 // Channels below this value will be excluded from graph due to low chance of routing success
}"
```

Note: Olympus config is provided as a command line argument instead of a file because it contains private keys (the one for storage tokens and for Strike). Don't forget to use space before issuing a command (i.e. `$ java -jar ...`, NOT `$java - jar ...`) so it does not get recorded in history.
