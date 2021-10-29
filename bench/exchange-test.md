# Nebula-Exchange test result
We use LDBC dataset to test the exchange client import performance.

# prepare
* The Nebula Schema DDL is configed in bench/NEBULA_DDL. 

* The exchange config file is configed in bench/EXCHANGE_CONFIG.

# import command

for space sf1, the command is:
```
spark-submit --master "spark://127.0.0.1:7077" \
--driver-memory=2G \
--num-executors=3 \
--executor-memory=10G \
--executor-cores=20 \
--class com.vesoft.nebula.exchange.Exchange \
nebula-exchange-2.6.0.jar -c app_sf1.conf
```

for space sf30, the command is:

```
spark-submit --master "spark://127.0.0.1:7077" \
--driver-memory=2G \
--num-executors=3 \
--executor-memory=30G \
--executor-cores=20 \
--class com.vesoft.nebula.exchange.Exchange \
nebula-exchange-2.6.0.jar -c app_sf30.conf
```

for space sf100, the command is:
```
spark-submit --master "spark://127.0.0.1:7077" \
--driver-memory=2G \
--num-executors=3 \
--executor-memory=30G \
--executor-cores=20 \
--class com.vesoft.nebula.exchange.Exchange \
nebula-exchange-2.6.0.jar -c app_sf100.conf
```

# import result
Here is the import result:

When Space has 1 replica, and the auto-compact is enable.

|  Dataset |             Data Amount          |cores|executor-memory|spark-partition|batch size|duration|   speed  |
|:--------:|:--------------------------------:|:---:|:-------------:|:-------------:|:--------:|:------:|:--------:|
|LDBC sf1  | vertex:3165488  edge:17256029    |  60 |       10G     |       60      |   2000   |  56s   | 360,000/s |
|LDBC sf30 | vertex:88673640 edge:540915215   |  60 |       20G     |       60      |   2000   | 7.5min |1399,086/s|
|LDBC sf100| vertex:282386021 edge:1775513185 |  60 |       30G     |       60      |   2000   | 27min  |1270,303/s|

When Space has 1 replica, and the auto-compact is false.

|  Dataset |             Data Amount          |cores|executor-memory|spark-partition|batch size|duration|   speed  |
|:--------:|:--------------------------------:|:---:|:-------------:|:-------------:|:--------:|:------:|:--------:|
|LDBC sf1  | vertex:3165488  edge:17256029    |  60 |       10G     |       60      |   2000   |   49s  | 416,765/s|
|LDBC sf30 | vertex:88673640 edge:540915215   |  60 |       20G     |       60      |   2000   |  6.3min|1665,578/s|
|LDBC sf100| vertex:282386021 edge:1775513185 |  60 |       30G     |       60      |   2000   |  22min |1559,014/s|

After data import, space sf100 with one replica will take to finish the manual compaction.



When Space has 3 replicas, and the auto-compact is closed.

|  Dataset  |            Data Amount           |cores|executor-memory|spark-partition|batch size|duration|  speed  |
|:---------:|:--------------------------------:|:---:|:-------------:|:-------------:|:--------:|:------:|:-------:|
|LDBC sf1   | vertex:3165488  edge:17256029    |  60 |     10G       |       60      |  2000    | 58s    |352,095/s |
|LDBC sf30  | vertex:88673640 edge:540915215   |  60 |     20G       |       60      |  2000    | 17min  |617,243/s|
|LDBC sf100 | vertex:282386021 edge:1775513185 |  60 |     30G       |       60      |  2000    | 42min  |816,623/s|

After data import, space sf100 with three replicas will take 1.1h to finish the manual compaction.

# other information
> The Spark cluster and nebula cluster are separated

> Spark cluster has three workers, nebula cluster has three metad, three graphd and three storaged. 

> The clusters have 10 Gigabit Network, each nebula machine has 1.5T SSD disk and 256G memory.

