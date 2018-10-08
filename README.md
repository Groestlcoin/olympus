# olympus
Maintenance server for Lightning Wallet

### Installation manual for Ubuntu 16.04

1. Install Java by following steps described at https://www.digitalocean.com/community/tutorials/how-to-install-java-with-apt-get-on-ubuntu-16-04

2. Install Bitcoin Core:
```
sudo apt-get update  
sudo apt-get install atomd
```

3. Bitcoin config file should contain the following lines: 
```
daemon=1
server=1

rpcuser=foo # set your own
rpcpassword=bar # set your own

rpcport=17332
port=7333

txindex=1 # recommended but not necessary
# prune=100000 not recommended but won't break anything if turned on, useful if you have less than 200Gb of disk space

zmqpubrawtx=tcp://127.0.0.1:29100
zmqpubhashblock=tcp://127.0.0.1:29100
zmqpubrawblock=tcp://127.0.0.1:29100
```

4. Install and run a MongoDB by following steps described at https://docs.mongodb.com/manual/tutorial/install-mongodb-on-ubuntu/

5. Open MongoDB console and issue the following commands:
```
$ mongo

> use bca-olympus
> db.spentTxs.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 180 } )
> db.spentTxs.createIndex( { "prefix": 1 }, { unique: true } )
> db.spentTxs.createIndex( { "txids": 1 } )

> db.scheduledTxs.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 120 } )
> db.scheduledTxs.createIndex( { "cltv": 1 } )

> db.userData.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 365 * 5 } )
> db.userData.createIndex( { "key": 1 } )

> use bca-blindSignatures
> db.blindTokens.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 365 } )
> db.blindTokens.createIndex( { "seskey": 1 }, { unique: true } )

> "0123456789".split('').forEach(function(v) { db["clearTokens" + v].createIndex( { "token": 1 }, { unique: true } ) })
```

6. Get Eclair fat JAR file, either by downloading it directly from a repository or by compiling from source:  
```
git clone https://github.com/btcontract/eclair-atom.git  
cd eclair  
mvn package  
```

7. Create an `eclairdata` directory and put an `eclair.conf` file there with the following lines:
```
eclair {
	chain = "test"
	spv = false

	server {
		public-ips = ["127.0.0.1"]
		binding-ip = "0.0.0.0"
		port = 9835
	}

	api {
		enabled = true
		binding-ip = "127.0.0.1"
		port = 8180
		password = "pass"
	}

	bitcoind {
		host = "localhost"
		rpcport = 17332
		rpcuser = "foo"
		rpcpassword = "bar"
		zmq = "tcp://127.0.0.1:29000"
	}
}

```

8. Run Ecliar instance by issuing `java -Declair.datadir=eclairdata/ -jar eclair-node.jar`

9. Get Olympus fat JAR file, either by downloading it directly from a repository or by compiling from source: 
```
git clone https://github.com/btcontract/olympus.git  
cd olympus  
sbt  
assembly  
```

10. Run Olympus instance by issuing:
```
$ java -jar bca-olympus-assembly-1.0.jar production "{\"zmqApi\":\"tcp://127.0.0.1:29001\",\"ip\":\"127.0.0.1\",\"privKey\":\"17237641984433455757821928886025053286790003625266087739786982589470995742521\",\"btcApi\":\"http://foo:bar@127.0.0.1:17332\",\"eclairSockPort\":9835,\"rewindRange\":144,\"eclairSockIp\":\"127.0.0.1\",\"eclairNodeId\":\"03dc39d7f43720c2c0f86778dfd2a77049fa4a44b4f0a8afb62f3921567de41375\",\"paymentProvider\":{\"quantity\":50,\"priceMsat\":5000000,\"url\":\"http://127.0.0.1:8180\",\"description\":\"Storage tokens for backup Olympus server at 127.0.0.1\",\"tag\":\"EclairProvider\",\"pass\":\"pass\"}}"
```

Note: Olympus config is provided as a command line argument instead of a file because it contains private keys (the one for storage tokens and for Strike). Don't forget to use space before issuing a command (i.e. `$ java -jar ...`, NOT `$java - jar ...`) so it does not get recorded in history.
