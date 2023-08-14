# 欢迎使用 NebulaGraph Exchange

[English](https://github.com/vesoft-inc/nebula-exchange/blob/master/README.md)

NebulaGraph Exchange（以下简称 Exchange）是一款 Apache Spark&trade; 应用，用于在分布式环境中将集群中的数据批量迁移到
NebulaGraph 中，它能支持多种不同格式的批式数据和流式数据的迁移，它还支持直接与 SST File 方式的
NebulaGraph 写入。

Exchange 支持的 Spark 版本包括 2.2、2.4 和
3.0，对应的工具包名分别为 `nebula-exchange_spark_2.2`、`nebula-exchange_spark_2.4`
和 `nebula-exchange_spark_3.0`。

> 注意：
> - 3.4.0 版本不支持 kafka 和 pulsar， 若需将 kafka 或 pulsar 数据导入 NebulaGraph，请使用 3.0.0 或
    3.3.0 或 3.5.0 版本。
> - 本仓库仅支持 NebulaGraph 2.x 和 3.x，如果您在使用 NebulaGraph
    v1.x，请使用 [NebulaExchange v1.0](https://github.com/vesoft-inc/nebula-java/tree/v1.0/tools/exchange)
    ，或参考 Exchange 1.0
    的使用文档[NebulaExchange 用户手册](https://docs.nebula-graph.com.cn/nebula-exchange/about-exchange/ex-ug-what-is-exchange/ "点击前往 Nebula Graph 网站")。

> 注意：3.4.0版本不支持 kafka 和 pulsar， 若需将 kafka 或 pulsar 数据导入 NebulaGraph，请使用 3.0.0 或
> 3.3.0 或 3.5.0 版本。

## 如何获取

1. 编译打包最新的 Exchange。

    ```bash
    $ git clone https://github.com/vesoft-inc/nebula-exchange.git
    $ cd nebula-exchange
    $ mvn clean package -Dmaven.test.skip=true -Dgpg.skip -Dmaven.javadoc.skip=true -pl nebula-exchange_spark_2.2 -am -Pscala-2.11 -Pspark-2.2
    $ mvn clean package -Dmaven.test.skip=true -Dgpg.skip -Dmaven.javadoc.skip=true -pl nebula-exchange_spark_2.4 -am -Pscala-2.11 -Pspark-2.4
    $ mvn clean package -Dmaven.test.skip=true -Dgpg.skip -Dmaven.javadoc.skip=true -pl nebula-exchange_spark_3.0 -am -Pscala-2.12 -Pspark-3.0 
    ```

   编译打包完成后，可以：
    - 在 nebula-exchange/nebula-exchange_spark_2.2/target/ 目录下找到
      nebula-exchange_spark_2.2-3.0-SNAPSHOT.jar 文件；
    - 在 nebula-exchange/nebula-exchange_spark_2.4/target/ 目录下找到
      nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar 文件；
    - 以及在 nebula-exchange/nebula-exchange_spark_3.0/target/ 目录下找到
      nebula-exchange_spark_3.0-3.0-SNAPSHOT.jar 文件。

3. 在官网或 GitHub 下载

   **正式版本**

   [GitHub Releases](https://github.com/vesoft-inc/nebula-exchange/releases)
   或者 [Downloads](https://www.nebula-graph.com.cn/release?exchange=)

   **快照版本**

   进入[GitHub Actions Artifacts](https://github.com/vesoft-inc/nebula-exchange/actions/workflows/snapshot.yml)
   页面点击任意 workflow 后，从 Artifacts 中，根据需求下载下载。

## 版本匹配

Exchange 和 NebulaGraph 的版本对应关系如下:

|              Exchange Version              | NebulaGraph Version |          Spark Version          |
|:------------------------------------------:|:-------------------:|:-------------------------------:|
|         nebula-exchange-2.0.0.jar          |    2.0.0, 2.0.1     |              2.4.*              |
|         nebula-exchange-2.0.1.jar          |    2.0.0, 2.0.1     |              2.4.*              |
|         nebula-exchange-2.1.0.jar          |    2.0.0, 2.0.1     |              2.4.*              |
|         nebula-exchange-2.5.0.jar          |    2.5.0, 2.5.1     |              2.4.*              |
|         nebula-exchange-2.5.1.jar          |    2.5.0, 2.5.1     |              2.4.*              |
|         nebula-exchange-2.5.2.jar          |    2.5.0, 2.5.1     |              2.4.*              |
|         nebula-exchange-2.6.0.jar          |    2.6.0, 2.6.1     |              2.4.*              |
|         nebula-exchange-2.6.1.jar          |    2.6.0, 2.6.1     |              2.4.*              |
|         nebula-exchange-2.6.2.jar          |    2.6.0, 2.6.1     |              2.4.*              |
|         nebula-exchange-2.6.3.jar          |    2.6.0, 2.6.1     |              2.4.*              |
|    nebula-exchange_spark_2.2-3.x.x.jar     |        3.x.x        |              2.2.*              |
|    nebula-exchange_spark_2.4-3.x.x.jar     |        3.x.x        |              2.4.*              |
|    nebula-exchange_spark_3.0-3.x.x.jar     |        3.x.x        | `3.0.*`,`3.1.*`,`3.2.*`,`3.3.*` |
| nebula-exchange_spark_2.2-3.0-SNAPSHOT.jar |       nightly       |              2.2.*              |
| nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar |       nightly       |              2.4.*              |
| nebula-exchange_spark_3.0-3.0-SNAPSHOT.jar |       nightly       | `3.0.*`,`3.1.*`,`3.2.*`,`3.3.*` |

## 使用说明

特性 & 注意事项：

*1. Nebula Graph 2.0 支持 String 类型和 Integer 类型的点 id 。*

*2. Exchange 2.0 新增 null、Date、DateTime、Time 类型数据的导入（ DateTime 是 UTC 时区，非 Local time）。*

*3. Exchange 2.0 支持 Hive on Spark 以外的 Hive 数据源，需在配置文件中配置 Hive
源，具体配置示例参考 [application.conf](https://github.com/vesoft-inc/nebula-exchange/blob/master/exchange-common/src/test/resources/application.conf)
中 Hive 的配置。*

*4. Exchange 2.0 将导入失败的 INSERT 语句进行落盘，存于配置文件的 error/output 路径中。*

*5. Exchange 2.5.0 支持SST导入，但不支持属性的 default 值。*

*6.
配置文件参考 [application.conf](https://github.com/vesoft-inc/nebula-exchange/blob/master/exchange-common/src/test/resources/application.conf)。*

*7. Exchange 2.0 的导入命令：*

```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange --master local nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar -c /path/to/application.conf
```

如果数据源有HIVE，则导入命令最后还需要加 `-h` 表示启用HIVE数据源。

注：在Yarn-Cluster模式下提交 Exchange，请使用如下提交命令：

```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange \
--master yarn-cluster \
--files application.conf \
--conf spark.driver.extraClassPath=./ \
--conf spark.executor.extraClassPath=./ \
nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar \
-c application.conf
```

注：使用 Nebula Exchange 进行 SST 文件生成时，会涉及到 Spark 的 shuffle 操作，请注意在提交命令中增加
spark.sql.shuffle.partition 的配置：

```
$SPARK_HOME/bin/spark-submit --class com.vesoft.nebula.exchange.Exchange \
--master local \
--conf spark.sql.shuffle.partitions=200 \
nebula-exchange_spark_2.4-3.0-SNAPSHOT.jar \
-c application.conf
```

关于 Nebula Exchange 的更多说明，请参考 Exchange 2.0
的[使用手册](https://docs.nebula-graph.com.cn/2.6.2/nebula-exchange/about-exchange/ex-ug-what-is-exchange/) 。

## 贡献

Nebula Exchange 2.0 是一个完全开源的项目，欢迎开源爱好者通过以下方式参与：

- 前往 [Nebula Graph 论坛](https://discuss.nebula-graph.com.cn/ "点击前往“Nebula Graph 论坛") 上参与
  Issue 讨论，如答疑、提供想法或者报告无法解决的问题
- 撰写或改进文档
- 提交优化代码
