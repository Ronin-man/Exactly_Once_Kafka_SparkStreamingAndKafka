package app

import java.text.SimpleDateFormat
import java.util.Date

import com.alibaba.fastjson.{JSON, JSONObject}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.kafka010.{HasOffsetRanges, OffsetRange}
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection.mutable.ListBuffer

/**
 * @author ronin
 * @create 2020-12-09 0:06
 */
object DauAPP2 {
    def main(args: Array[String]): Unit = {
        //创建配置文件对象 注意：Streaming程序至少不能设置为local，至少需要2个线程
        val conf: SparkConf = new SparkConf().setAppName("Spark01_W").setMaster("local[4]")

        //创建Spark Streaming上下文环境对象O
        val ssc = new StreamingContext(conf, Seconds(3))

        val gmallstartup = "GMALL_STARTUP_0105"
        val daugroup = "DAU_GROUP"

        //使用偏移量工具类从redis获取上一次的kafka偏移量,对应的每个分区消费的offset
        val partitionToLong = util.OffsetManager.getOffset(gmallstartup, daugroup)

        //判断是否第一次消费，如果不是则从偏移量开始消费数据流
        var inputStream: InputDStream[ConsumerRecord[String, String]] = null
        if (partitionToLong != null && partitionToLong.size > 0) {
            inputStream = util.MyKafkaUtil.getKafkaStream(gmallstartup, ssc, partitionToLong, daugroup)
        } else {
            inputStream = util.MyKafkaUtil.getKafkaStream(gmallstartup, ssc)
        }

        // 得到本批次的偏移量的结束位置，用于更新redis中的偏移量
        var offsetRanges: Array[OffsetRange] = Array.empty[OffsetRange]
        val inputGetOffsetDstream: DStream[ConsumerRecord[String, String]] = inputStream.transform { rdd =>
            offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges //driver? executor?  //周期性的执行
            rdd
        }

        //补充日志json时间字段
        val value1 = inputGetOffsetDstream.map(record => {
            val str = record.value()
            val nObject = JSON.parseObject(str)
            val long = nObject.getLong("ts")
            val str1 = new SimpleDateFormat("yyyy-MM-dd HH").format(new Date(long))
            val strings = str1.split(" ")
            nObject.put("dt", strings(0))
            nObject.put("hr", strings(1))
            nObject
        })

        //写入redis以及设置保存时间为24小时，并通过是否写入redis成功判断过滤条数
        val value2 = value1.mapPartitions(iter => {
            val client = util.RedisUtil.getJedisClient
            val buffer = new ListBuffer[JSONObject]()
            val list = iter.toList
            println("过滤前:" + list.size)
            for (jsonObj <- list) {
                val str = jsonObj.getString("dt")
                val str1 = jsonObj.getJSONObject("common").getString("mid")
                val str2 = "dau:" + str
                val long = client.sadd(str2, str1)
                client.expire(str2, 3600 * 24)
                if (long == 1) {
                    buffer += jsonObj
                }
            }
            client.close()
            println("过滤后:" + buffer.size)
            list.toIterator
        })


        //写入es
        value2.foreachRDD { rdd => {
            rdd.foreachPartition(rdd => {
                val list = rdd.toList
                val tuples = list.map(jsonObj => {
                    val nObject = jsonObj.getJSONObject("common")
                    val info = bean.DauInfo(nObject.getString("mid"),
                        nObject.getString("uid"),
                        nObject.getString("ar"),
                        nObject.getString("ch"),
                        nObject.getString("vc"),
                        jsonObj.getString("dt"),
                        jsonObj.getString("hr"),
                        "00",
                        jsonObj.getLong("ts"))
                    (info.mid, info)
                })
                val str = new SimpleDateFormat("yyyy-MM-dd").format(new Date())
                util.MyEsUtil.bulkDoc(tuples, "gmall_dau_info_" + str)
            })
        }
            util.OffsetManager.saveOffset(gmallstartup, daugroup, offsetRanges)
        }

        //    value.map(_.value()).print()
        //启动采集器
        ssc.start()
        //默认情况下，上下文对象不能关闭
        //ssc.stop()
        //等待采集结束，终止上下文环境对象
        ssc.awaitTermination()
    }
}