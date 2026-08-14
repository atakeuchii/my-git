(defproject my-git "0.1.0-SNAPSHOT"
  :description "A minimal Git implementation in Clojure"
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.12.5"]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]]}}
  :repl-options {:init-ns my-git.core})
