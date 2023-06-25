# NebulaGraph Exchange
 [中文版](https://github.com/vesoft-inc/nebula-exchange/blob/master/README-CN.md)

NebulaGraph Exchange (referred to as Exchange) is an Apache Spark™ application used to migrate data in bulk from different sources to NebulaGraph in a distributed way(Spark). It supports a variety of batch or streaming data sources and allows direct writing to NebulaGraph through side-loading (SST Files).

Exchange supports Spark versions 2.2, 2.4, and 3.0 along with their respective toolkits named: `nebula-exchange_spark_2.2`, `nebula-exchange_spark_2.4`, and `nebula-exchange_spark_3.0`.

> Note:
> - Exchange 3.4.0 does not support Apache Kafka and Apache Pulsar. Please use Exchange of version 3.0.0, 3.3.0, or 3.5.0 to load data from Apache Kafka or Apache Pulsar to NebulaGraph for now.
> - This repo covers only NebulaGraph 2.x and 3.x, for NebulaGraph v1.x, please use [NebulaGraph Exchange v1.0](https://github.com/vesoft-inc/nebula-java/tree/v1.0/tools/exchange).

## Build or Download Exchange

1. Build the latest Exchange

    ```bash
    $ git clone https://github.com/vesoft-inc/nebula-exchange.git
    $ cd nebula-exchange
    $ mvn clean package -Dmaven.test.skip=true -Dgpg.skip -Dmaven.javadoc.skip=true -pl nebula-exchange_spark_2.2 -am -Pscala-2.11 -Pspark-2.2
    $ mvn clean package -Dmaven.test.skip=true -Dgpg.skip -Dmaven.javadoc.skip=true -pl nebula-exchange_spark_2.4 -am -Pscala-2.11 -Pspark-2.4
    $ mvn clean package -Dmaven.test.skip=true -Dgpg.skip -Dmaven.javadoc.skip=true -pl nebula-exchange_spark_3.0 -am -Pscala-2.12 -Pspark-3.0
    ```

    After packaging, the newly generated JAR files can be found in the following path:
    - nebula-exchange/nebula-exchange_spark_2.2/target/ contains nebula-exchange_spark_2.2-3.0-SNAPSHOT.jar
    - nebula-exchange/nebula-exchange_spark_2.4/target/ contains nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar
    - nebula-exchange/nebula-exchange_spark_3.0/target/ contains nebula-exchange_spark_3.0-3.0-SNAPSHOT.jar

3. Download from the GitHub artifact
   
   **Released Version:**

   [GitHub Releases](https://github.com/vesoft-inc/nebula-exchange/releases)
   or [Downloads](https://www.nebula-graph.io/release?exchange=)

   **Snapshot Version:**

   [GitHub Actions Artifacts](https://github.com/vesoft-inc/nebula-exchange/actions/workflows/snapshot.yml)

## Get Started

Here is an example command to run the Exchange:

```bash
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange --master local nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar -c /path/to/application.conf
```

And when the source is **Hive**, run:

```bash
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange --master local nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar -c /path/to/application.conf -h
```

Run the Exchange in **Yarn-Cluster** mode:

```bash
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange \
--master yarn-cluster \
--files application.conf \
--conf spark.driver.extraClassPath=./ \
--conf spark.executor.extraClassPath=./ \
nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar \
-c application.conf
```

Note: When using Exchange to generate SST files, please add `spark.sql.shuffle.partition` in `--conf` for Spark's shuffle operation:

```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange \
--master local \
--conf spark.sql.shuffle.partitions=200 \
nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar \
-c application.conf
```

For more details, please refer to [NebulaGraph Exchange Docs](https://docs.nebula-graph.io/master/nebula-exchange/about-exchange/ex-ug-what-is-exchange/)

## Version Compatibility Matrix

Here is the version correspondence between Exchange and NebulaGraph:

| Exchange Version | Nebula Version | Spark Version |
|:----------------:|:--------------:|:--------------:|
|nebula-exchange-2.0.0.jar|  2.0.0, 2.0.1  |2.4.*|
|nebula-exchange-2.0.1.jar|  2.0.0, 2.0.1  |2.4.*|
|nebula-exchange-2.1.0.jar|  2.0.0, 2.0.1  |2.4.*|
|nebula-exchange-2.5.0.jar|  2.5.0, 2.5.1  |2.4.*|
|nebula-exchange-2.5.1.jar|  2.5.0, 2.5.1  |2.4.*|
|nebula-exchange-2.5.2.jar|  2.5.0, 2.5.1  |2.4.*|
|nebula-exchange-2.6.0.jar|  2.6.0, 2.6.1  |2.4.*|
|nebula-exchange-2.6.1.jar|  2.6.0, 2.6.1  |2.4.*|
|nebula-exchange-2.6.2.jar|  2.6.0, 2.6.1  |2.4.*|
|nebula-exchange-2.6.3.jar|  2.6.0, 2.6.1  |2.4.*|
|nebula-exchange_spark_2.2-3.0.0.jar|  3.x.x  |2.2.*|
|nebula-exchange_spark_2.4-3.0.0.jar|  3.x.x  |2.4.*|
|nebula-exchange_spark_3.0-3.0.0.jar|  3.x.x  |`3.0.*`,`3.1.*`,`3.2.*`,`3.3.*`|
|nebula-exchange_spark_2.2-3.3.0.jar|  3.x.x  |2.2.*|
|nebula-exchange_spark_2.4-3.3.0.jar|  3.x.x  |2.4.*|
|nebula-exchange_spark_3.0-3.3.0.jar|  3.x.x  |`3.0.*`,`3.1.*`,`3.2.*`,`3.3.*`|
|nebula-exchange_spark_2.2-3.0-SNAPSHOT.jar|     nightly    |2.2.*|
|nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar|     nightly    |2.4.*|
|nebula-exchange_spark_3.0-3.0-SNAPSHOT.jar|     nightly    |`3.0.*`,`3.1.*`,`3.2.*`,`3.3.*`|

## Feature History

1. Exchange allows for the import of vertex data with both String and Integer type IDs.
2. Exchange also supports importing data of various types, including Null, Date, DateTime (using UTC instead of local time), and Time.
3. In addition to Hive on Spark, Exchange can import data from other Hive sources as well.
4. If there are failures during the data import process, Exchange supports recording and retrying the INSERT statement.
5. While SST import is supported by Exchange, property default values are not yet supported.
6. Exchange is compatible with Spark 2.2, Spark 2.4, and Spark 3.0.

Refer to [application.conf](https://github.com/vesoft-inc/nebula-exchange/blob/master/exchange-common/src/test/resources/application.conf) as an example to edit the configuration file.
