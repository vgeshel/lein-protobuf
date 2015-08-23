(ns leiningen.protobuf
  (:use [clojure.string :only [join]]
        [leiningen.javac :only [javac]]
        [leinjacker.eval :only [in-project]]
        [leiningen.core.user :only [leiningen-home]]
        [leiningen.core.main :only [abort]])
  (:require [clojure.java.io :as io]
            [fs.core :as fs]
            [fs.compression :as fs-zip]
            [conch.core :as sh]))

(def cache (io/file (leiningen-home) "cache" "lein-protobuf"))
(def default-version "2.6.1")

(defn version [project]
  (or (:protobuf-version project) default-version))

(defn zipfile [project]
  (io/file cache (format "protobuf-%s.zip" (version project))))

(defn srcdir [project]
  (io/file cache (str "protobuf-" (version project))))

(defn protoc
  "If the user has protobuf installed, she can specify the location of protoc in project. Otherwise we will download and build it and store it in leiningen cache."
  [project]
  (or (when-let [path (:protoc project)]
        (io/file path))
      (io/file (srcdir project) "src" "protoc")))

(defn url [project]
  (java.net.URL.
   (format "https://github.com/google/protobuf/releases/download/v%s/protobuf%s-%s.zip"
           (version project)
           (if (neg? (compare "3" (version project)))
             "-java"
             "")
           (version project))))

(defn proto-path [project]
  (io/file (get project :proto-path "resources/proto")))

(def ^{:dynamic true} *compile-protobuf?* true)

(defn target [project]
  (doto (io/file (:target-path project))
    .mkdirs))

(defn extract-dependencies
  "Extract all files proto depends on into dest."
  [project proto-path protos dest]
  (in-project (dissoc project :prep-tasks)
    [proto-path (.getPath proto-path)
     dest (.getPath dest)
     protos protos]
    (ns (:require [clojure.java.io :as io]))
    (letfn [(dependencies [proto-file]
              (when (.exists proto-file)
                (for [line (line-seq (io/reader proto-file))
                      :when (.startsWith line "import")]
                  (second (re-matches #".*\"(.*)\".*" line)))))]
      (loop [deps (mapcat #(dependencies (io/file proto-path %)) protos)]
        (when-let [[dep & deps] (seq deps)]
          (let [proto-file (io/file dest dep)]
            (if (or (.exists (io/file proto-path dep))
                    (.exists proto-file))
              (recur deps)
              (do (.mkdirs (.getParentFile proto-file))
                  (when-let [resource (io/resource (str "proto/" dep))]
                    (io/copy (io/reader resource) proto-file))
                  (recur (concat deps (dependencies proto-file)))))))))))

(defn modtime [f]
  (let [files (if (fs/directory? f)
                (->> f io/file file-seq rest)
                [f])]
    (if (empty? files)
      0
      (apply max (map fs/mod-time files)))))

(defn proto-file? [file]
  (let [name (.getName file)]
    (and (.endsWith name ".proto")
         (not (.startsWith name ".")))))

(defn proto-files [dir]
  (for [file (rest (file-seq dir)) :when (proto-file? file)]
    (.substring (.getPath file) (inc (count (.getPath dir))))))

(defn fetch
  "Fetch protocol-buffer source and unzip it."
  [project]
  (let [zipfile (zipfile project)
        srcdir  (srcdir project)]
    (when-not (.exists zipfile)
      (.mkdirs cache)
      (println (format "Downloading %s to %s" (.getName zipfile) zipfile))
      (with-open [stream (.openStream (url project))]
        (io/copy stream zipfile)))
    (when-not (.exists srcdir)
      (println (format "Unzipping %s to %s" zipfile srcdir))
      (fs-zip/unzip zipfile cache))))

(defn build-protoc
  "Compile protoc from source."
  [project]
  (let [srcdir (srcdir project)
        protoc (protoc project)]
    (when-not (.exists protoc)
      (fetch project)
      (fs/chmod "+x" (io/file srcdir "configure"))
      (fs/chmod "+x" (io/file srcdir "install-sh"))
      (println "Configuring protoc")
      (sh/stream-to-out (sh/proc "./configure" :dir srcdir) :out)
      (println "Running 'make'")
      (sh/stream-to-out (sh/proc "make" :dir srcdir) :out))))

(defn compile-protobuf
  "Create .java and .class files from the provided .proto files."
  ([project protos]
     (compile-protobuf project protos (io/file (target project) "protosrc")))
  ([project protos dest]
     (let [target     (target project)
           class-dest (io/file target "classes")
           proto-dest (io/file target "proto")
           proto-path (proto-path project)
           protoc-path (.getPath (protoc project))
           protoc-parent (fs/parent (protoc project))]
       (when (or (> (modtime proto-path) (modtime dest))
                 (> (modtime proto-path) (modtime class-dest)))
         (binding [*compile-protobuf?* false]
           (fs/mkdirs target)
           (fs/mkdirs class-dest)
           (fs/mkdirs proto-dest)
           (.mkdirs dest)
           (extract-dependencies project proto-path protos proto-dest)
           (doseq [proto protos]
             (let [args (into [protoc-path proto
                               (str "--java_out=" (.getAbsoluteFile dest)) "-I."]
                              (map #(str "-I" (.getAbsoluteFile %))
                                   [proto-dest proto-path protoc-parent]))]
               (println " > " (join " " args))
               (let [result (apply sh/proc (concat args [:dir proto-path]))]
                 (when-not (= (sh/exit-code result) 0)
                   (abort "ERROR:" (sh/stream-to-string result :err))))))
           (javac (-> project
                      (update-in [:java-source-paths] concat [(.getPath dest)])
                      (update-in [:javac-options] concat ["-Xlint:none"]))))))))

(defn compile-google-protobuf
  "Compile com.google.protobuf.*"
  [project]
  (fetch project)
  (let [srcdir (srcdir project)
        descriptor "google/protobuf/descriptor.proto"
        src (io/file srcdir "src" descriptor)
        dest (io/file (proto-path project) descriptor)]
    (.mkdirs (.getParentFile dest))
    (when (> (modtime src) (modtime dest))
      (io/copy src dest))
    (compile-protobuf project [descriptor]
                      (io/file srcdir "java" "src" "main" "java"))))

(defn protobuf
  "Task for compiling protobuf libraries."
  [project & files]
  (let [files (or (seq files)
                  (proto-files (proto-path project)))]
    (build-protoc project)
    (when (and (= "protobuf" (:name project)))
      (compile-google-protobuf project))
    (compile-protobuf project files)))
