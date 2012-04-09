(ns ClojureWpf.core
  (:import [System.Windows.Markup XamlReader]
           [System.Threading Thread ApartmentState ParameterizedThreadStart ThreadStart EventWaitHandle EventResetMode]
           [System.Windows.Threading Dispatcher DispatcherObject DispatcherPriority DispatcherUnhandledExceptionEventHandler]
           [System.Windows Application Window EventManager DependencyProperty FrameworkPropertyMetadata LogicalTreeHelper]
           [System.Windows.Data BindingBase Binding BindingOperations]
           [System.Reflection BindingFlags PropertyInfo MethodInfo EventInfo]
           [System.ComponentModel PropertyDescriptor MemberDescriptor]
           [System.Xaml XamlSchemaContext]
           [System.Collections ICollection]))

(def ^:dynamic *cur* nil)

(defn with-invoke* [dispatcher-obj func]
  (let [dispatcher (.get_Dispatcher dispatcher-obj)]
    (if (.CheckAccess dispatcher)
      (func)
      (.Invoke dispatcher DispatcherPriority/Normal 
                    (sys-func [Object] [] (func))))))

(defmacro with-invoke [dispatcher-obj & body]
  `(ClojureWpf.core/with-invoke* ~dispatcher-obj (fn [] ~@body)))

(defn with-begin-invoke* [dispatcher-obj func]
  (let [dispatcher (.get_Dispatcher dispatcher-obj)]
    (if (.CheckAccess dispatcher)
      (func)
      (.BeginInvoke dispatcher DispatcherPriority/Normal 
                    (sys-func [Object] [] (func))))))

(defmacro with-begin-invoke [dispatcher-obj & body]
  `(ClojureWpf.core/with-begin-invoke* ~dispatcher-obj (fn [] ~@body)))

(defn find-elem [target path] (reduce #(LogicalTreeHelper/FindLogicalNode % (name %2)) target path))

(defn find-elem-warn [target path]
  (or (find-elem target path) (println "Unable to find " path " in " target)))

(defn- compile-target-expr [target]
  (let [path? (vector? target)
        dispatcher-obj (if path? (first target) target)
        path-expr (when path? (vec (rest target)))
        target (if path?
          `(ClojureWpf.core/find-elem-warn ~dispatcher-obj ~path-expr)
          target)]
    [dispatcher-obj target]))

(defmacro doat [target & body]
  (let [[dispatcher-obj target] (compile-target-expr target)]
    `(ClojureWpf.core/with-invoke ~dispatcher-obj
       (clojure.core/binding [ClojureWpf.core/*cur* ~target]
                             ~@body))))


(def *dispatcher-exception (atom nil))

(defn- dispatcher-unhandled-exception [sender args]
  (let [ex (.get_Exception args)]
    (reset! *dispatcher-exception ex)
    (println "Dispatcher Exception: " ex)
    (.set_Handled args true)))

(defn separate-threaded-window
  [& {:keys [exception-handler]}]
  (let [window (atom nil)
        ex-handler (or exception-handler dispatcher-unhandled-exception)
        waitHandle (EventWaitHandle. false EventResetMode/AutoReset)
        thread (doto (Thread.
                   (gen-delegate ParameterizedThreadStart [window]
                                 (reset! window (Window.))
                                 (.set_Title @window "Window")
                                 (.Show @window)
                                 (.add_UnhandledException Dispatcher/CurrentDispatcher
                                                          (gen-delegate DispatcherUnhandledExceptionEventHandler [s e]
                                                                        (ex-handler s e)))
                                 (.Set waitHandle)
                                 (Dispatcher/Run)))
               (.SetApartmentState ApartmentState/STA)
               (.Start window))]
    (.WaitOne waitHandle)
    {:thread thread :window @window}))
    
(defn app-start [application-class]
  (doto (Thread.
         (gen-delegate ThreadStart [] (.Run (Activator/CreateInstance application-class))))
    (.SetApartmentState ApartmentState/STA)
    (.Start)))

(def dev-sandbox-setter (atom nil))

(defn dev-sandbox []
  (let [sandbox (separate-threaded-window)
        window (:window sandbox)]
    (reset! dev-sandbox-setter
            (fn [func] (with-invoke window (.set_Content window (func)))))
    sandbox))

(defn sandbox-load [func]
  (when @dev-sandbox-setter
    (@dev-sandbox-setter (fn [] (func :dev true)))))

(defn set-live-reload! [enable])

(defn- load-dev-xaml [path]
  (let [xaml (slurp path :econding "UTF8")]
    (XamlReader/Parse xaml)))

(defn xamlview
  ([constructor
    mutator
    dev-xaml-path]
     (let [func (fn [& {:keys [dev]}]
                  (let [elem (if (and dev dev-xaml-path) (load-dev-xaml dev-xaml-path) (constructor))]
                    (mutator elem)
                    elem))]
       func)))

(defprotocol IAttachedData (attach [this target value]))

(defrecord AttachedData [^DependencyProperty prop]
  clojure.lang.IDeref
  (deref [this] (when *cur* (.GetValue *cur* prop)))
  IAttachedData
  (attach [this target value] (.SetValue target prop value))
  clojure.lang.IFn
  (invoke [this target] (.GetValue target prop)))

(defn create-attached-data [^DependencyProperty prop] (AttachedData. prop))

(defn- event-dg-helper [target evt-method-info handler]
  (let [dg (if-not (instance? Delegate handler)
                 (gen-delegate (.ParameterType (aget (.GetParameters evt-method-info) 0))
                               [s e] (binding [*cur* target] (handler s e)))
                 handler)]
        (.Invoke evt-method-info target (to-array [dg]))
        dg))

(defn- event-helper [target event-key handler prefix]
  (let [mname (str prefix (name event-key))]
    (if-let [m (.GetMethod (.GetType target) mname)]
      (event-dg-helper target m handler)
      (throw (System.MissingMethodException. (str (.GetType target)) mname)))))

(defn += [target event-key handler] (event-helper target event-key handler "add_"))

(defn -= [target event-key handler] (event-helper target event-key handler "remove_"))

(defn get-static-field [type fname]
  (when-let [f (.GetField type fname (enum-or BindingFlags/Static BindingFlags/Public))]
      (.GetValue f nil)))

(defn get-static-field-throw [type fname]
  (or (get-static-field type fname) (throw (System.MissingFieldException. (str type) fname))))

(defn find-dep-prop [type key]
  (get-static-field type (str (name key) "Property")))

(defn find-routed-event [type key]
  (get-static-field type (str (name key) "Event")))

(defn bind [target key binding]
  (let [dep-prop (if (instance? DependencyProperty key) key (find-dep-prop target key))]
    (BindingOperations/SetBinding target dep-prop binding)))

(declare caml)

(defn- mutate-prop [target prop-info func]
  (let [val (.GetValue prop-info target nil)
        mutated (func val)]
    (when (.CanWrite prop-info)
      (.SetValue prop-info target mutated nil)
      mutated)))

(defn- set-prop-collection [target prop-info val]
  (if-let [existing (.GetValue prop-info target nil)]
    (if (instance? ICollection val)
      (do (println "Replacing collection " existing " with " val)
          (.Clear existing)
          (doseq [x val] (.Add existing x)))
      (println "Don't know how to apply vector to " existing))
    (println "No existing collection for " target " " prop-info)))

(defn- pset-property-closure [type prop-info]
  (let [dep-prop (find-dep-prop type (.Name prop-info))]
    (fn [target val]
      (cond (fn? val) (mutate-prop target prop-info val)
        (vector? val) (set-prop-collection target prop-info val)
        :default (.SetValue prop-info target val nil)))))

(defn- pset-event-closure [type event-info]
  (let [add-method (.GetAddMethod event-info)]
    (fn [target value] (event-dg-helper target add-method value))))

(defn- pset-method-closure [type method-info]
  (fn [target value] (.Invoke method-info target (to-array value))))

(defn- pset-compile-member-key [type key]
  (let [name (name key)]
        (let [members (.GetMember type name)]
          (if-let [member (first members)]
            (do
              (cond
               (instance? PropertyInfo member) (pset-property-closure type member)
               (instance? EventInfo member) (pset-event-closure type member)
               (instance? MethodInfo member) (pset-method-closure type member)
               :default (throw (InvalidOperationException. (str "Don't know how to handle " member " on " type)))))
            (throw (MissingMemberException. (str type) name))))))

(defn- pset-compile-keyword [type kw]
  (let [])
  (cond ;(instance? BindingBase val) (fn [t v] (bind t kw v))
        (= key :*cur*) (fn [t v] (v t)) ; Invoke val on current target
        :default (pset-compile-member-key type kw)))

(defn pset-compile-key [type key]
  (cond
   (keyword? key) (pset-compile-keyword type key)
   (instance? AttachedData key) (fn [t v] (attach key t v))
   (instance? DependencyProperty key) (throw (NotImplementedException.))
   :default (throw (ArgumentException. (str "Don't know how to handle key " key)))))

(defn pset-exec [target & setters]
  (let [type (.GetType target)]
    (doat target
      (doseq [[key val] (partition 2 setters)]
        ((pset-compile-key type key) target val)))))

(defmacro ^:private pset-compile [type target setters]
  (let 
    `(do ~@funcs)))

(defn ^:private when-type? [t] (eval `(clojure.core/when (clojure.core/instance? System.Type ~t) ~t)))

(defn pset-compile [type setters]
  (let [mutator-vals (for [[key val] (partition 2 setters)]
                       [(pset-compile-key type key) val])
        tsym (gensym "t")
        exprs (for [[m v] mutator-vals] `(~m ~tsym ~v))]
    `(fn [~tsym] (binding [ClojureWpf.core/*cur* ~tsym] ~@exprs))))

(defmacro pset!* [type target setters]
  (if-let [type (when-type? type)]
    (let [compiled (pset-compile type target setters)]
      `((~compiled ~target) target))
    `(ClojureWpf.core/pset-exec ~target ~@setters)))

(defmacro pset! [& forms]
  (let [type-target? (first forms)
        type (when-type? type-target?) 
        target (if type (second forms) type-target?)
        setters (if type (nnext forms) (next forms))]
    `(ClojureWpf.core/pset!* ~type ~target ~setters)))

(defmacro defattached [name & opts]
  (let [qname (str *ns* "/" (clojure.core/name name))]
    `(clojure.core/defonce ~name
       (ClojureWpf.core/create-attached-data
        (System.Windows.DependencyProperty/RegisterAttached
         ~qname System.Object System.Object
         (ClojureWpf.core/pset! (System.Windows.FrameworkPropertyMetadata.)
                                :Inherits true ~@opts))))))

(defn- split-attrs-forms [forms]
  (let [was-key (atom false)]
    (split-with (fn [x]
                  (cond
                   (not (list? x)) (do (reset! was-key true) true)
                   @was-key (do (reset! was-key false) true)
                   :default false)) forms)))
(declare at*)

(defmacro at* [target forms]
  (let [[target-attrs forms] (split-attrs-forms forms)
        xforms (for [form forms]
                 (let [path (first form)
                       setters (rest form)]
                   `(ClojureWpf.core/at* (ClojureWpf.core/find-elem-warn ~target ~path) ~setters)))]
    `(do (ClojureWpf.core/pset! ~target ~@target-attrs)
         ~@xforms)))

(defmacro at [target & forms]
  (let [[dispatcher-obj target] (compile-target-expr target)]
    `(ClojureWpf.core/with-invoke ~dispatcher-obj
       (ClojureWpf.core/at* ~target ~forms))))

(def xaml-map
  (apply assoc {}
         (mapcat (fn [xt] [(.get_Name xt) xt])
                 (.GetAllXamlTypes (XamlSchemaContext.) "http://schemas.microsoft.com/winfx/2006/xaml/presentation"))))

(declare caml-compile)

(defn caml-children*-expr [invoker elem children]
  `(let [existing# (.GetValue ~invoker ~elem)]
    (if (and existing# (instance? ICollection existing#))
      (doseq [ch# ~children] (.Add existing# ch#))
      (.SetValue ~invoker ~elem ~children))))

(defn caml-children-expr [xt type elem children]
  (when (seq children)
    (let [children* (vec (for [ch children]
                           (if (and (list? ch) (keyword? (first ch)))
                             (caml-compile ch) ch)))
          cp (.get_ContentProperty xt)
          invoker (.get_Invoker cp)]
      (if (= 1 (count children*))
        (let [content (first children*)] `(.SetValue ~invoker ~elem ~content))
                      (caml-children*-expr invoker elem children*)))))

(defn caml-compile [form]
  (let [nexpr (name (first form))
        enidx (.IndexOf nexpr "#")
        ename (when (> enidx 0) (.Substring nexpr (inc enidx)))
        tname (if ename (.Substring nexpr 0 enidx) nexpr)
        xt (xaml-map tname)]
    (when xt
      (let [type (.get_UnderlyingType xt)
            elem (gensym "e")
            forms (if ename [`(.set_Name ~elem ~ename)] [])
            more (rest form)
            attrs? (first more)
            pset-fn (when (vector? attrs?)
                      (pset-compile type attrs?))
            forms (if pset-fn (conj forms pset-fn) forms)
            children (if pset-fn (rest more) more)
            children-expr (caml-children-expr xt type elem children)
            forms (if children-expr (conj forms children-expr) forms)]
        `(let [~elem (System.Activator/CreateInstance ~type)]
           ~@forms
           ~elem)))))

(defmacro caml [form]
  (let [compiled (caml-compile form)]
    `~compiled))