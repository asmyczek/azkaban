;Copyright 2010 LinkedIn, Inc
;
;Licensed under the Apache License, Version 2.0 (the "License"); you may not
;use this file except in compliance with the License. You may obtain a copy of
;the License at
;
;http://www.apache.org/licenses/LICENSE-2.0
;
;Unless required by applicable law or agreed to in writing, software
;distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
;WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
;License for the specific language governing permissions and limitations under
;the License.

(ns com.linkedin.mr-kluj.avro-storage
  (:require [com.linkedin.mr-kluj.job :as job]
            [simple-avro.core :as avro]
            [simple-avro.utils :as avro-utils]
            [clojure.contrib.str-utils2 :as str-utils])
	(:import (com.linkedin.json LatestExpansionFunction))
  (:import (org.apache.hadoop.conf Configuration))
  (:import (org.apache.hadoop.mapreduce Job InputSplit TaskAttemptContext RecordWriter))
	(:import (org.apache.hadoop.mapreduce.lib.input FileInputFormat))
	(:import (org.apache.hadoop.mapreduce.lib.output FileOutputFormat))
  (:import (org.apache.hadoop.fs FileSystem Path))
  (:import (org.apache.hadoop.io Text BytesWritable NullWritable
                                 SequenceFile SequenceFile$CompressionType SequenceFile$Metadata))
	(:import (org.apache.log4j Logger)))

(def *logger* (Logger/getLogger "avro-storage"))

;
; Util functions
;

(defn job-param
  "Delayed evaluation for config params as input or output path
   that are computed dynamically in job setup."
  [param job]
  (if (fn? param) (param (.getConfiguration job)) (str param)))

;
; AvroRecordReader
;

(gen-class
  :name       com.linkedin.mr-kluj.avro-storage.AvroRecordReader
  :prefix     arr-
  :state      state
  :init       init
  :implements [clojure.lang.IDeref]
  :extends    org.apache.hadoop.mapreduce.RecordReader)

(defn arr-initialize
	[this #^InputSplit split #^TaskAttemptContext context]
 (let [conf   (.getConfiguration context)
       path   (.getPath split)
       fs     (.getFileSystem path conf)
       reader (avro-utils/avro-reader (.open fs path))]
   (reset! (.state this) {:reader  reader :value nil})))

(defn arr-getCurrentKey
  [this]
  nil)

(defn arr-getCurrentValue
  [this]
  (:value @this))

(defn arr-getProgress
  [this]
  0.0)

(defn arr-nextKeyValue
  [this]
  (let [reader  (:reader @this)]
    (if (avro-utils/has-next reader)
      (let [value (avro-utils/read-next reader)]
        (swap! (.state this) assoc :value value)
        true)
      false)))

(defn arr-close
  [this]
  (avro-utils/close (:reader @this))
  (reset! (.state this) nil))

(defn arr-init
  []
  [[] (atom {})])

(defn arr-deref
  [this]
  @(.state this))

;
; AvroInputFormat
;

(gen-class
  :name    com.linkedin.mr-kluj.avro-storage.AvroInputFormat
  :prefix  aif-
  :extends org.apache.hadoop.mapreduce.lib.input.FileInputFormat)

(defn aif-createRecordReader
  [this #^InputSplit split #^TaskAttemptContext context]
    (com.linkedin.mr-kluj.avro-storage.AvroRecordReader.))

;
; AvroOutputFormat
;

(gen-class
  :name    com.linkedin.mr-kluj.avro-storage.AvroOutputFormat
  :prefix  aof-
  :extends org.apache.hadoop.mapreduce.lib.output.FileOutputFormat)

(defn- data-file-writer
  "Write data to a single avro file. This format is used when no key schema
   is provided to avro-storage-output. The file is written using avro
   DataFileWriter and can be read using DataFileReader."
  [file-output-format context schema]
  (let [conf   (.getConfiguration context)
        path   (.getDefaultWorkFile file-output-format context "")
        fs     (.getFileSystem path conf)
        schema (avro/avro-schema schema)
        writer (avro-utils/avro-writer (.create fs path) schema)]
    (proxy [RecordWriter] []
      (write [key value]
             (avro-utils/write writer value)
             (.progress context))
      (close [#^TaskAttemptContext context]
             (avro-utils/close writer)))))
  
(defn- custom-sequence-writer
  "Writes data to a Sequence file and serializes key and values
   using avro and provided key and value schema."
  [file-output-format context key-schema value-schema]
  (let [conf         (.getConfiguration context)
        path         (.getDefaultWorkFile file-output-format context "")
        fs           (.getFileSystem path conf)
        meta         (doto (SequenceFile$Metadata.)
                       (.set (Text. "key.schema") (Text. key-schema))
                       (.set (Text. "value.schema") (Text. value-schema)))
        writer       (SequenceFile/createWriter fs conf path
                         (.getOutputKeyClass context)
                         (.getOutputValueClass context)
                         SequenceFile$CompressionType/NONE, nil, context, meta)]
    (proxy [RecordWriter] []
      (write [key value]
             (.append writer
                      (BytesWritable. (avro/pack key-schema key avro/binary-encoder))
                      (BytesWritable. (avro/pack value-schema value avro/binary-encoder)))
           (.progress context))
      (close [#^TaskAttemptContext context]
             (.close writer)))))

(defn aof-getRecordWriter
  "Recorder constructor, generates sequence or avro wirter."
  [this #^TaskAttemptContext context]
  (let [conf         (.getConfiguration context)
        key-schema   (.get conf "output.key.schema")
        value-schema (.get conf "output.value.schema")]
    (if key-schema
      (custom-sequence-writer this context key-schema value-schema)
      (data-file-writer this context value-schema))))

;
; Public API
;

(let [config (Configuration.)
      fs (FileSystem/get config)
      latest-expansion-function (LatestExpansionFunction. fs *logger*)
      latest-exp-fn (fn [path] (.apply latest-expansion-function path))]
  (defn avro-storage-input
    "Avro file storage input. Provide file location or a job-param function which
     takes the job Configuration as input parameter and returns avro file location.
     The function can be used to determine input file location dynamically in the setup stage.
     Records are provided as value to the mapper, key is always nil."
    [path]
    (when (nil? path) (throw (RuntimeException. "Input path cannot be null.")))
    (job/add-config
      (fn [#^Job job]
        (let [path         (job-param path job)
              actual-paths (str-utils/join "," (map latest-exp-fn (str-utils/split path #",")))]
          (.info #^Logger *logger* (format "Avro Input: Given paths[%s] which resolved to paths[%s]" path actual-paths))
          (doto job
            (.setInputFormatClass com.linkedin.mr-kluj.avro-storage.AvroInputFormat)
            (FileInputFormat/addInputPaths #^String actual-paths)))))))

(defn avro-intermediate-data
  "Intermediate data is serialized using avro binary serialization
   and provided key and value schemas."
  [key-schema value-schema]
  (job/intermediate-serialization
      (fn [#^Job job]
        (doto job
          (.setMapOutputKeyClass BytesWritable)
          (.setMapOutputValueClass BytesWritable)))
      (fn [key value context]
        [(BytesWritable. (avro/pack key-schema key avro/binary-encoder))
         (BytesWritable. (avro/pack value-schema value avro/binary-encoder))])
      (fn [#^BytesWritable key values context]
        [(avro/unpack key-schema (.getBytes key) avro/binary-decoder)
         (map (fn [#^BytesWritable val] (avro/unpack value-schema (.getBytes val) avro/binary-decoder)) values)])))

(defn avro-storage-output
  "Avro storage output. Provide the output path or a job-param function as described in avro-storage-input
   and key/value avro schemas. Key schema is optional. If provided, the a SequenceFile is generated with
   binary serialized keys and values. If key schema is nil, the output file is written using avro DataFileWriter."
  [path #^String key-schema #^String value-schema]
  (job/add-config
    (fn [#^Job job]
      (when (nil? path) (throw (RuntimeException. (format "Output on job[%s] cannot be null." (.getJobName job)))))
      (let [path (job-param path job)]
	      (doto job
	        (.setOutputKeyClass (if key-schema BytesWritable NullWritable))
	        (.setOutputValueClass BytesWritable)
	        (.setOutputFormatClass com.linkedin.mr-kluj.avro-storage.AvroOutputFormat)
	        (FileOutputFormat/setOutputPath (Path. path)))
	      (when key-schema (.set (.getConfiguration job) "output.key.schema" (avro/json-schema key-schema)))
	      (when value-schema (.set (.getConfiguration job) "output.value.schema" (avro/json-schema value-schema))))
      job)))

;
; Example job, count first names
;
(comment
  
  ; Output schema 
  (defavro-record NameCount
  :first avro-string
  :count avro-int)
  
  (job/run 
	  (job/staged-job ["avro-job" "staging-location"]
	    (avro/avro-storage-input "input.avro")
	    (job/map-mapper (fn [key value context] [[(value "first-name") 1]]))
	    (job/create-reducer (fn [key values context] [[nil {"first" key "count" (count values)}]]))
	    (avro/avro-intermediate-data avro-string avro-int)
	    (avro/avro-storage-output "count.avro" nil NameCount)))
)
