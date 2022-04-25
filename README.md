# Nebula Exchange
 [中文版](https://github.com/vesoft-inc/nebula-exchange/blob/master/README-CN.md)
 
Nebula Exchange (Exchange for short) is an Apache Spark application. It is used to migrate cluster data in bulk from Spark to Nebula Graph in a distributed environment. It supports migration of batch data and streaming data in various formats.

Exchange only supports Nebula Graph 2.x and 3.x.

If you want to import data for Nebula Graph v1.x，please use [Nebula Exchange v1.0](https://github.com/vesoft-inc/nebula-java/tree/v1.0/tools/exchange).

Exchange currently supports spark2.2, spark2.4 and spark3.0, and the corresponding toolkits are nebula-exchange_spark_2.2,  nebula-exchange_spark_2.4, nebula-exchange_spark_3.0.

## How to get

1. Package latest Exchange

    ```bash
    $ git clone https://github.com/vesoft-inc/nebula-exchange.git
    $ cd nebula-exchange
    $ mvn clean package -Dmaven.test.skip=true -Dgpg.skip -Dmaven.javadoc.skip=true -pl nebula-exchange_spark_2.2 -am -Pscala-2.11 -Pspark-2.2
    $ mvn clean package -Dmaven.test.skip=true -Dgpg.skip -Dmaven.javadoc.skip=true -pl nebula-exchange_spark_2.4 -am -Pscala-2.11 -Pspark-2.4
    $ mvn clean package -Dmaven.test.skip=true -Dgpg.skip -Dmaven.javadoc.skip=true -pl nebula-exchange_spark_3.0 -am -Pscala-2.12 -Pspark-3.0
    ```

    After the packaging, you can see the newly generated nebula-exchange_spark_2.2-3.0-SNAPSHOT.jar under the nebula-exchange/nebula-exchange_spark_2.2/target/ directory,
    nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar under the nebula-exchange/nebula-exchange_spark_2.4/target/ directory, 
    nebula-exchange_spark_3.0-3.0-SNAPSHOT.jar under the nebula-exchange/nebula-exchange_spark_3.0/target/ directory.
2. Download from github artifact
   
   **release version:**
   
   https://github.com/vesoft-inc/nebula-exchange/releases
   or https://nebula-graph.com.cn/release/?exchange
   
   **snapshot version:**
   
   https://github.com/vesoft-inc/nebula-exchange/actions/workflows/deploy_snapshot.yml
## How to use

Import command:
```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange --master local nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar -c /path/to/application.conf
```
If your source is HIVE, import command is:
```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange --master local nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar -c /path/to/application.conf -h
```

Note：Submit Exchange with Yarn-Cluster mode, please use following command：
```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange \
--master yarn-cluster \
--files application.conf \
--conf spark.driver.extraClassPath=./ \
--conf spark.executor.extraClassPath=./ \
nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar \
-c application.conf
```

Note: When use Exchange to generate SST files, please add spark.sql.shuffle.partition config for Spark's shuffle operation:
```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange \
--master local \
--conf spark.sql.shuffle.partitions=200 \
nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar \
-c application.conf
```

For more details about Exchange, please refer to [Exchange 2.0](https://docs.nebula-graph.io/2.6.2/16.eco-tools/1.nebula-exchange/) .

## Version match

There are the version correspondence between Nebula Exchange and Nebula:

| Nebula Exchange Version | Nebula Version |
|:-----------------------:|:--------------:|
|       2.0.0             |  2.0.0, 2.0.1  |
|       2.0.1             |  2.0.0, 2.0.1  |
|       2.1.0             |  2.0.0, 2.0.1  |
|       2.5.0             |  2.5.0, 2.5.1  |
|       2.5.1             |  2.5.0, 2.5.1  |
|       2.5.2             |  2.5.0, 2.5.1  |
|       2.6.0             |  2.6.0, 2.6.1  |
|       2.6.1             |  2.6.0, 2.6.1  |
|       2.6.2             |  2.6.0, 2.6.1  |
|       2.6.3             |  2.6.0, 2.6.1  |
|       3.0.0             |  3.0.x, 3.1.x  |
|     3.0-SNAPSHOT        |     nightly    |

## New Features

1. Supports importing vertex data with String and Integer type IDs.
2. Supports importing data of the Null, Date, DateTime, and Time types(DateTime uses UTC, not local time).
3. Supports importing data from other Hive sources besides Hive on Spark.
4. Supports recording and retrying the INSERT statement after failures during data import.
5. Supports SST import, but not support property's default value yet.
6. Supports Spark 2.2, Spark 2.4 and Spark 3.0.

Refer to [application.conf](https://github.com/vesoft-inc/nebula-exchange/blob/master/exchange-common/src/test/resources/application.conf) as an example to edit the configuration file.
