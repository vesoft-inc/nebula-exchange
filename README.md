# Nebula Exchange 2.0
 [中文版](https://github.com/vesoft-inc/nebula-exchange/blob/master/README-CN.md)
 
Nebula Exchange (Exchange for short) is an Apache Spark application. It is used to migrate cluster data in bulk from Spark to Nebula Graph in a distributed environment. It supports migration of batch data and streaming data in various formats.

Exchange 2.0 only supports Nebula Graph 2.0 . If you want to import data for Nebula Graph v1.x，please use [Nebula Exchange v1.0](https://github.com/vesoft-inc/nebula-java/tree/v1.0/tools/exchange).

## How to get

1. Package latest Exchange

    ```bash
    $ git clone https://github.com/vesoft-inc/nebula-exchange.git
    $ cd nebula-exchange/nebula-exchange
    $ mvn clean package -Dmaven.test.skip=true -Dgpg.skip -Dmaven.javadoc.skip=true
    ```

    After the packaging, you can see the newly generated nebula-exchange-2.6.1.jar under the nebula-exchange/nebula-exchange/target/ directory.
2. Download from Maven repository
   
   release version:
   https://repo1.maven.org/maven2/com/vesoft/nebula-exchange/
   
   snapshot version:
   https://oss.sonatype.org/content/repositories/snapshots/com/vesoft/nebula-exchange/
## How to use

Import command:
```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange --master local nebula-exchange-2.6.1.jar -c /path/to/application.conf
```
If your source is HIVE, import command is:
```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange --master local nebula-exchange-2.6.1.jar -c /path/to/application.conf -h
```

Note：Submit Exchange with Yarn-Cluster mode, please use following command：
```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange \
--master yarn-cluster \
--files application.conf \
--conf spark.driver.extraClassPath=./ \
--conf spark.executor.extraClassPath=./ \
nebula-exchange-2.6.1.jar \
-c application.conf
```

Note: When use Exchange to generate SST files, please add spark.sql.shuffle.partition config for Spark's shuffle operation:
```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange \
--master local \
--conf spark.sql.shuffle.partitions=200 \
nebula-exchange-2.6.1.jar \
-c application.conf
```

For more details about Exchange, please refer to [Exchange 2.0](https://docs.nebula-graph.io/2.0.1/16.eco-tools/1.nebula-exchange/) .

## Version match

There are the version correspondence between Nebula Exchange and Nebula:

| Nebula Exchange Version | Nebula Version |
|:-----------------------:|:--------------:|
|       2.0.0             |  2.0.0, 2.0.1  |
|       2.0.1             |  2.0.0, 2.0.1  |
|       2.1.0             |  2.0.0, 2.0.1  |
|       2.5.0             |  2.5.0, 2.5.1  |
|       2.5.1             |  2.5.0, 2.5.1  |
|       2.6.0             |  2.6.0, 2.6.1  |
|       2.6.1             |  2.6.0, 2.6.1  |
|     2.5-SNAPSHOT        |     nightly    |

## New Features

1. Supports importing vertex data with String and Integer type IDs.
2. Supports importing data of the Null, Date, DateTime, and Time types(DateTime uses UTC, not local time).
3. Supports importing data from other Hive sources besides Hive on Spark.
4. Supports recording and retrying the INSERT statement after failures during data import.
5. Supports SST import, but not support property's default value yet.

Refer to [application.conf](https://github.com/vesoft-inc/nebula-exchange/tree/master/nebula-exchange/src/main/resources/application.conf) as an example to edit the configuration file.
