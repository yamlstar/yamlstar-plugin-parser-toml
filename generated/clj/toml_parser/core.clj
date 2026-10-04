(ns toml-parser.core (:require ys.v0))
(ys.v0/init)
(declare read-value node-events)
(def max-depth 128)
(def newline (C 10))
(def tab (C 9))
(def carriage-return (C 13))
(defn-
 parser-error
 [state message]
 (let
  [text
   (str "TOML parse error at line " (get+ state (quote line)))
   text
   (add+
    text
    (str ", column " (get+ state (quote column)) (C 58) " " message))
   data
   (%
    :line
    (get+ state (quote line))
    :column
    (get+ state (quote column))
    :offset
    (get+ state (quote index)))]
  (throw (ex-info text data))))
(defn-
 initial-state
 [text]
 (let
  [crlf (add+ (S carriage-return) (S newline))]
  (when
   (has? (+replace text crlf "") (S carriage-return))
   (let
    [data
     (% :line 1 :column 1 :offset 0)
     message
     "TOML parse error: bare carriage return"]
    (throw (ex-info message data))))
  (let
   [chars (V (+replace text crlf (S newline)))]
   (% :chars chars :index 0 :line 1 :column 1))))
(defn
 peek-char
 ([state] (peek-char state 0))
 ([state offset]
  (let
   [index (add+ (get+ state (quote index)) offset)]
   (get+ (get+ state (quote chars)) index))))
(defn-
 advance
 [state]
 (let
  [c (peek-char state)]
  (if
   c
   (put
    (put
     (update state :index inc)
     :line
     (if
      (= c newline)
      (inc+ (get+ state (quote line)))
      (get+ state (quote line))))
    :column
    (if (= c newline) 1 (inc+ (get+ state (quote column)))))
   state)))
(defn- advance-n [state n] (nth (iterate advance state) n))
(defn-
 starts-with?
 [state text]
 (=
  (seq text)
  (map (fn [& [_1]] (peek-char state _1)) (range (count text)))))
(defn-
 expect-char
 [state expected]
 (if
  (= expected (peek-char state))
  (advance state)
  (parser-error state (str "expected " (pr-str expected)))))
(defn- horizontal-space? [c] (or (= c (C " ")) (= c tab)))
(defn-
 skip-horizontal
 [state]
 (loop
  [state state]
  (if
   (horizontal-space? (peek-char state))
   (recur (advance state))
   state)))
(defn- control-char? [c] (and c (or (< (int c) 32) (= (int c) 127))))
(defn-
 read-comment
 [state]
 (loop
  [state state chars []]
  (let
   [c (peek-char state)]
   (cond
    (or (nil? c) (= c newline))
    (do (let [comment (join chars)] (vector comment state)))
    (and (control-char? c) (not= c tab))
    (parser-error state "control character in comment")
    :else
    (recur (advance state) (conj chars c))))))
(defn-
 join-comments
 [comments]
 (when (seq comments) (join comments (S newline))))
(defn-
 codepoint-string
 [value]
 (try
  (S (C value))
  (catch
   Exception
   e
   (let
    [value
     (sub+ value 65536)
     high
     (add+ 55296 (quot value 1024))
     low
     (add+ 56320 (rem value 1024))]
    (add+ (S (C high)) (S (C low)))))))
(defn-
 hex-value
 [c]
 (cond
  (le (int (C "0")) (int c) (int (C "9")))
  (sub+ (int c) (int (C "0")))
  (le (int (C "a")) (int c) (int (C "f")))
  (add+ 10 (sub+ (int c) (int (C "a"))))
  (le (int (C "A")) (int c) (int (C "F")))
  (add+ 10 (sub+ (int c) (int (C "A"))))))
(defn-
 read-unicode-escape
 [state digits]
 (loop
  [state state left digits value 0]
  (if
   (falsey? left)
   (if
    (or (> value 1114111) (le 55296 value 57343))
    (parser-error state "invalid Unicode scalar value")
    (vector (codepoint-string value) state))
   (do
    (let
     [digit (hex-value (peek-char state))]
     (if
      digit
      (recur (advance state) (dec+ left) (add+ (mul+ value 16) digit))
      (parser-error state "invalid Unicode escape")))))))
(defn-
 read-basic-escape
 [state]
 (let
  [c (peek-char state) state (advance state)]
  (cond
   (= c (C "b"))
   [(S (C 8)) state]
   (= c (C "t"))
   [(S tab) state]
   (= c (C "n"))
   [(S newline) state]
   (= c (C "f"))
   [(S (C 12)) state]
   (= c (C "r"))
   [(S carriage-return) state]
   (= c (C "e"))
   [(S (C 27)) state]
   (= c (C "\""))
   ["\"" state]
   (= c (C 92))
   [(S (C 92)) state]
   (= c (C "x"))
   (read-unicode-escape state 2)
   (= c (C "u"))
   (read-unicode-escape state 4)
   (= c (C "U"))
   (read-unicode-escape state 8)
   :else
   (parser-error state "invalid escape sequence"))))
(defn-
 trim-first-newline
 [state]
 (if (= (peek-char state) newline) (advance state) state))
(defn-
 quote-run
 [state quote]
 (loop [n 0] (if (= quote (peek-char state n)) (recur (inc+ n)) n)))
(defn-
 read-basic-string
 [state multiline?]
 (let
  [width
   (if multiline? 3 1)
   state
   (advance-n state width)
   state
   (if multiline? (trim-first-newline state) state)]
  (loop
   [state state out []]
   (let
    [c (peek-char state)]
    (cond
     (nil? c)
     (parser-error state "unterminated basic string")
     (and multiline? (= c (C "\"")))
     (do
      (let
       [run (quote-run state (C "\""))]
       (cond
        (< run 3)
        (recur (advance-n state run) (into out (repeat run (C "\""))))
        (le run 5)
        (do
         (let
          [value (join (into out (repeat (sub+ run 3) (C "\""))))]
          (vector value (advance-n state run))))
        :else
        (parser-error state "too many quotes at string end"))))
     (and (falsey? multiline?) (= c (C "\"")))
     (vector (join out) (advance state))
     (and (falsey? multiline?) (= c newline))
     (parser-error state "newline in basic string")
     (= c (C 92))
     (do
      (let
       [after (skip-horizontal (advance state))]
       (if
        (and multiline? (= (peek-char after) newline))
        (do
         (let
          [state
           (advance (skip-horizontal (advance state)))
           state
           (loop
            [state state]
            (let
             [c (peek-char state)]
             (if
              (or (horizontal-space? c) (= c newline))
              (recur (advance state))
              state)))]
          (recur state out)))
        (do
         (let
          [[value state] (read-basic-escape (advance state))]
          (recur state (into out value)))))))
     (and
      (control-char? c)
      (falsey? (and multiline? (= c newline)))
      (not= c tab))
     (parser-error state "control character in basic string")
     :else
     (recur (advance state) (conj out c)))))))
(defn-
 read-literal-string
 [state multiline?]
 (let
  [width
   (if multiline? 3 1)
   state
   (advance-n state width)
   state
   (if multiline? (trim-first-newline state) state)]
  (loop
   [state state out []]
   (let
    [c (peek-char state)]
    (cond
     (nil? c)
     (parser-error state "unterminated literal string")
     (and multiline? (= c (C "'")))
     (do
      (let
       [run (quote-run state (C "'"))]
       (cond
        (< run 3)
        (recur (advance-n state run) (into out (repeat run (C "'"))))
        (le run 5)
        (do
         (let
          [value (join (into out (repeat (sub+ run 3) (C "'"))))]
          (vector value (advance-n state run))))
        :else
        (parser-error state "too many quotes at string end"))))
     (and (falsey? multiline?) (= c (C "'")))
     (vector (join out) (advance state))
     (and (falsey? multiline?) (= c newline))
     (parser-error state "newline in literal string")
     (and
      (control-char? c)
      (falsey? (and multiline? (= c newline)))
      (not= c tab))
     (parser-error state "control character in literal string")
     :else
     (recur (advance state) (conj out c)))))))
(defn-
 bare-key-char?
 [c]
 (and
  c
  (or
   (le (int (C "a")) (int c) (int (C "z")))
   (le (int (C "A")) (int c) (int (C "Z")))
   (le (int (C "0")) (int c) (int (C "9")))
   (= c (C "_"))
   (= c (C "-")))))
(defn-
 read-bare-key
 [state]
 (loop
  [state state out []]
  (let
   [c (peek-char state)]
   (if
    (bare-key-char? c)
    (recur (advance state) (conj out c))
    (if
     (seq out)
     (vector (join out) state)
     (parser-error state "expected key"))))))
(defn-
 read-key
 [state]
 (cond
  (starts-with? state "\"\"\"")
  (parser-error state "multiline string cannot be a key")
  (= (peek-char state) (C "\""))
  (read-basic-string state false)
  (starts-with? state "'''")
  (parser-error state "multiline string cannot be a key")
  (= (peek-char state) (C "'"))
  (read-literal-string state false)
  :else
  (read-bare-key state)))
(defn-
 read-key-path
 [state]
 (loop
  [state (skip-horizontal state) path []]
  (let
   [[key state]
    (read-key state)
    state
    (skip-horizontal state)
    path
    (conj path key)]
   (if
    (= (peek-char state) (C "."))
    (recur (skip-horizontal (advance state)) path)
    (vector path state)))))
(defn-
 decimal-mul-add
 [digits base addend]
 (loop
  [input (reverse digits) carry addend output []]
  (let
   [digit (first input)]
   (if
    digit
    (do
     (let
      [value (add+ (mul+ digit base) carry)]
      (recur (rest input) (quot value 10) (conj output (rem value 10)))))
    (do
     (let
      [output
       (loop
        [output output carry carry]
        (if
         (pos? carry)
         (recur (conj output (rem carry 10)) (quot carry 10))
         output))]
      (V (reverse output))))))))
(defn- base-digit [c] (or (hex-value c) -1))
(defn-
 base-to-decimal
 [text base]
 (let
  [digits (remove (fn [& [_1]] (= _1 (C "_"))) text)]
  (loop
   [chars digits result [0]]
   (let
    [c (first chars)]
    (if
     c
     (do
      (let
       [digit (base-digit c)]
       (when
        (or (< digit 0) (ge digit base))
        (throw (ex-info "invalid integer digit" (%))))
       (recur (rest chars) (decimal-mul-add result base digit))))
     (do
      (let
       [chars (+map result (fn [& [_1]] (C (add+ (int (C "0")) _1))))]
       (join chars))))))))
(defn-
 strip-leading-zeroes
 [text]
 (let
  [stripped (join (+drop-while text (fn [& [_1]] (= _1 (C "0")))))]
  (if (= stripped "") "0" stripped)))
(defn-
 decimal-in-range?
 [negative? digits]
 (let
  [limit
   (if negative? "9223372036854775808" "9223372036854775807")
   digits
   (strip-leading-zeroes digits)]
  (or
   (< (count digits) (count limit))
   (and
    (= (count digits) (count limit))
    (falsey? (pos? (compare digits limit)))))))
(defn-
 normalize-integer
 [token state]
 (let
  [negative?
   (starts? token "-")
   positive?
   (starts? token "+")
   signed?
   (or negative? positive?)
   unsigned
   (if signed? (subs token 1) token)
   [base digits]
   (cond
    (starts? unsigned "0x")
    [16 (subs unsigned 2)]
    (starts? unsigned "0o")
    [8 (subs unsigned 2)]
    (starts? unsigned "0b")
    [2 (subs unsigned 2)]
    :else
    [10 unsigned])
   pattern
   (case
    base
    16
    #"[0-9A-Fa-f]+(?:_[0-9A-Fa-f]+)*"
    8
    #"[0-7]+(?:_[0-7]+)*"
    2
    #"[01]+(?:_[01]+)*"
    #"(?:0|[1-9][0-9]*(?:_[0-9]+)*)")]
  (when-not
   (+re-matches digits pattern)
   (parser-error state "invalid integer"))
  (when
   (and (not= base 10) (or negative? positive?))
   (parser-error state "base-prefixed integer cannot have a sign"))
  (let
   [decimal
    (if
     (= base 10)
     (+replace digits "_" "")
     (base-to-decimal digits base))]
   (when-not
    (decimal-in-range? negative? decimal)
    (parser-error state "integer outside signed 64-bit range"))
   (let
    [decimal
     (strip-leading-zeroes decimal)
     prefix
     (if (and negative? (not= decimal "0")) "-" "")]
    (add+ prefix decimal)))))
(defn-
 leap-year?
 [year]
 (or
  (falsey? (rem year 400))
  (and (falsey? (rem year 4)) (truey? (rem year 100)))))
(defn-
 valid-date?
 [year month day]
 (let
  [days
   (case
    month
    1
    31
    3
    31
    5
    31
    7
    31
    8
    31
    10
    31
    12
    31
    4
    30
    6
    30
    9
    30
    11
    30
    2
    (if (leap-year? year) 29 28)
    0)]
  (le 1 day days)))
(defn- parse-small-int [text] (I (strip-leading-zeroes text)))
(def date-pattern #"([0-9]{4})-([0-9]{2})-([0-9]{2})")
(def time-pattern-text "([0-9]{2}):([0-9]{2})")
(def
 time-pattern-text
 (add+ time-pattern-text "(?::([0-9]{2})(\\.[0-9]+)?)?"))
(def time-pattern (re-pattern time-pattern-text))
(def datetime-pattern-text "([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt ]")
(def
 datetime-pattern-text
 (add+ datetime-pattern-text "([0-9]{2}):([0-9]{2})"))
(def
 datetime-pattern-text
 (add+ datetime-pattern-text "(?::([0-9]{2})(\\.[0-9]+)?)?"))
(def
 datetime-pattern-text
 (add+ datetime-pattern-text "(Z|z|[+-][0-9]{2}:[0-9]{2})?"))
(def datetime-pattern (re-pattern datetime-pattern-text))
(defn-
 normalize-temporal
 [token state]
 (let
  [missing nil datetime-match (+re-matches token datetime-pattern)]
  (if
   datetime-match
   (do
    (let
     [[_ y m d h minute second fraction offset]
      datetime-match
      year
      (parse-small-int y)
      month
      (parse-small-int m)
      day
      (parse-small-int d)
      hour
      (parse-small-int h)
      minute-number
      (parse-small-int minute)
      second-number
      (parse-small-int (or second "00"))
      valid?
      (and
       (valid-date? year month day)
       (le 0 hour 23)
       (le 0 minute-number 59)
       (le 0 second-number 59))]
     (when-not valid? (parser-error state "invalid date or time"))
     (when
      (and offset (falsey? (in? offset #{"z" "Z"})))
      (let
       [[_ oh om]
        (+re-matches offset #"[+-]([0-9]{2}):([0-9]{2})")
        valid?
        (and
         (le 0 (parse-small-int oh) 23)
         (le 0 (parse-small-int om) 59))]
       (when-not valid? (parser-error state "invalid date-time offset"))))
     (let
      [second
       (or second "00")
       offset
       (when offset (if (eq offset "z") "Z" offset))
       value
       (str y "-" m "-" d "T" h ":" minute ":")
       value
       (add+ value (str second fraction offset))
       type
       (if offset "datetime" "datetime-local")]
      (% :value value :type type))))
   (do
    (let
     [date-match (+re-matches token date-pattern)]
     (if
      date-match
      (do
       (let
        [[_ y m d]
         date-match
         valid?
         (valid-date?
          (parse-small-int y)
          (parse-small-int m)
          (parse-small-int d))]
        (when-not valid? (parser-error state "invalid date"))
        (% :value token :type "date-local")))
      (do
       (let
        [time-match (+re-matches token time-pattern)]
        (if
         time-match
         (do
          (let
           [[_ h minute second fraction]
            time-match
            second
            (or second "00")
            valid?
            (and
             (le 0 (parse-small-int h) 23)
             (le 0 (parse-small-int minute) 59)
             (le 0 (parse-small-int second) 59))]
           (when-not valid? (parser-error state "invalid time"))
           (let
            [value (str h ":" minute ":" second fraction)]
            (% :value value :type "time-local"))))
         missing)))))))))
(def float-pattern-text "[+-]?(?:")
(def
 float-pattern-text
 (add+ float-pattern-text "(?:0|[1-9][0-9]*(?:_[0-9]+)*)"))
(def
 float-pattern-text
 (add+ float-pattern-text "\\.[0-9]+(?:_[0-9]+)*"))
(def
 float-pattern-text
 (add+ float-pattern-text "(?:[eE][+-]?[0-9]+(?:_[0-9]+)*)?"))
(def float-pattern-text (add+ float-pattern-text "|"))
(def
 float-pattern-text
 (add+ float-pattern-text "(?:0|[1-9][0-9]*(?:_[0-9]+)*)"))
(def
 float-pattern-text
 (add+ float-pattern-text "[eE][+-]?[0-9]+(?:_[0-9]+)*"))
(def float-pattern-text (add+ float-pattern-text ")"))
(def float-pattern (re-pattern float-pattern-text))
(defn
 scalar-node
 ([value] (scalar-node value nil nil))
 ([value style] (scalar-node value style nil))
 ([value style toml-type]
  (% :node :scalar :value value :style style :toml-type toml-type)))
(defn-
 read-token
 [state]
 (loop
  [state state out []]
  (let
   [c
    (peek-char state)
    done?
    (or
     (nil? c)
     (= c newline)
     (= c (C "#"))
     (= c (C ","))
     (= c (C "]"))
     (= c (C "}")))]
   (if
    done?
    (vector (trimr (join out)) state)
    (recur (advance state) (conj out c))))))
(defn-
 parse-token
 [token state]
 (let
  [temporal
   (normalize-temporal token state)
   integer?
   (or
    (+re-matches token #"[+-]?(?:0|[1-9][0-9_]*|0[xob].+)")
    (+re-matches token #"0[xob].+"))]
  (cond
   (= token "true")
   (scalar-node "true" nil "bool")
   (= token "false")
   (scalar-node "false" nil "bool")
   (in? token #{"+inf" "inf" "-inf"})
   (do
    (let
     [value (if (= token "-inf") "-.inf" ".inf")]
     (scalar-node value nil "float")))
   (in? token #{"+nan" "-nan" "nan"})
   (scalar-node ".nan" nil "float")
   temporal
   (scalar-node
    (get+ temporal (quote value))
    "double"
    (get+ temporal (quote type)))
   (+re-matches token float-pattern)
   (scalar-node (+replace token "_" "") nil "float")
   integer?
   (scalar-node (normalize-integer token state) nil "integer")
   :else
   (parser-error state (str "invalid value " (pr-str token))))))
(defn
 map-node
 ([origin] (map-node origin nil nil))
 ([origin head line]
  (%
   :node
   :map
   :origin
   origin
   :entries
   []
   :index
   (%)
   :head
   head
   :line
   line)))
(defn- seq-node [items] (% :node :seq :items (V items)))
(defn-
 entry-at
 [table key]
 (when-some
  [index (get+ (get+ table (quote index)) key)]
  (get+ (get+ table (quote entries)) index)))
(defn-
 replace-entry
 [table key entry]
 (assoc-in table [:entries (get+ (get+ table (quote index)) key)] entry))
(defn-
 add-entry
 [table entry state]
 (let
  [key (get+ entry (quote key))]
  (when
   (contains? (get+ table (quote index)) key)
   (parser-error state (str "duplicate key " (pr-str key))))
  (update
   (assoc-in table [:index key] (count (get+ table (quote entries))))
   :entries
   (fn [& [_1]] (conj _1 entry)))))
(defn-
 aot-node?
 [node]
 (and (= (get+ node (quote node)) :seq) (get+ node (quote aot))))
(defn-
 alter-current-aot
 [node f]
 (let
  [index (dec+ (count (get+ node (quote items))))]
  (assoc-in
   node
   [:items index]
   (f (get+ (get+ node (quote items)) index)))))
(defn-
 insert-relative
 [table path value head line state]
 (when
  (get+ table (quote sealed))
  (parser-error state "cannot extend an inline table"))
 (let
  [key (first path)]
  (if
   (= (count path) 1)
   (do
    (let
     [entry (% :key key :value (put value :line line) :head head)]
     (add-entry table entry state)))
   (do
    (let
     [entry (entry-at table key)]
     (if
      entry
      (do
       (let
        [child
         (get+ entry (quote value))
         child
         (cond
          (and
           (= (get+ child (quote node)) :map)
           (not= (get+ child (quote origin)) :header))
          (insert-relative child (rest path) value head line state)
          (aot-node? child)
          (parser-error
           state
           "dotted key cannot extend an array of tables")
          (= (get+ child (quote node)) :map)
          (parser-error
           state
           "dotted key cannot extend a defined table")
          :else
          (parser-error state "key path crosses a scalar value"))]
        (replace-entry table key (put entry :value child))))
      (do
       (let
        [child
         (insert-relative
          (map-node :dotted)
          (rest path)
          value
          head
          line
          state)
         entry
         (% :key key :value child)]
        (add-entry table entry state)))))))))
(defn-
 alter-table-at
 [table path f state]
 (if
  (empty? path)
  (f table)
  (do
   (let
    [key (first path) entry (entry-at table key)]
    (when-not
     entry
     (parser-error state "internal table path is missing"))
    (let
     [child
      (get+ entry (quote value))
      child
      (cond
       (= (get+ child (quote node)) :map)
       (alter-table-at child (rest path) f state)
       (aot-node? child)
       (alter-current-aot
        child
        (fn [& [_1]] (alter-table-at _1 (rest path) f state)))
       :else
       (parser-error state "table path crosses a scalar value"))]
     (replace-entry table key (put entry :value child)))))))
(defn-
 declare-table-path
 [table path head line state]
 (when
  (get+ table (quote sealed))
  (parser-error state "cannot extend an inline table"))
 (let
  [key
   (first path)
   final?
   (= (count path) 1)
   entry
   (entry-at table key)]
  (if
   entry
   (do
    (let
     [child (get+ entry (quote value))]
     (cond
      (aot-node? child)
      (if
       final?
       (parser-error
        state
        "array of tables cannot be redefined as a table")
       (do
        (let
         [child
          (alter-current-aot
           child
           (fn
            [& [_1]]
            (declare-table-path _1 (rest path) head line state)))]
         (replace-entry table key (put entry :value child)))))
      (not= (get+ child (quote node)) :map)
      (parser-error state "table conflicts with an existing value")
      final?
      (if
       (= (get+ child (quote origin)) :implicit)
       (do
        (let
         [child (put child :origin :header :head head :line line)]
         (replace-entry table key (put entry :value child))))
       (parser-error state "table is already defined"))
      :else
      (do
       (let
        [child (declare-table-path child (rest path) head line state)]
        (replace-entry table key (put entry :value child)))))))
   (if
    final?
    (do
     (let
      [entry (% :key key :value (map-node :header head line))]
      (add-entry table entry state)))
    (do
     (let
      [child
       (declare-table-path
        (map-node :implicit)
        (rest path)
        head
        line
        state)
       entry
       (% :key key :value child)]
      (add-entry table entry state)))))))
(defn-
 declare-aot-path
 [table path head line state]
 (when
  (get+ table (quote sealed))
  (parser-error state "cannot extend an inline table"))
 (let
  [key
   (first path)
   final?
   (= (count path) 1)
   entry
   (entry-at table key)]
  (if
   entry
   (do
    (let
     [child (get+ entry (quote value))]
     (cond
      (and final? (aot-node? child))
      (do
       (let
        [item
         (map-node :aot-item head line)
         child
         (update child :items (fn [& [_1]] (conj _1 item)))]
        (replace-entry table key (put entry :value child))))
      final?
      (parser-error
       state
       "array of tables conflicts with existing value")
      (aot-node? child)
      (do
       (let
        [child
         (alter-current-aot
          child
          (fn
           [& [_1]]
           (declare-aot-path _1 (rest path) head line state)))]
        (replace-entry table key (put entry :value child))))
      (= (get+ child (quote node)) :map)
      (do
       (let
        [child (declare-aot-path child (rest path) head line state)]
        (replace-entry table key (put entry :value child))))
      :else
      (parser-error state "array of tables path crosses a value"))))
   (if
    final?
    (do
     (let
      [child
       (put (seq-node [(map-node :aot-item head line)]) :aot true)
       entry
       (% :key key :value child)]
      (add-entry table entry state)))
    (do
     (let
      [child
       (declare-aot-path
        (map-node :implicit)
        (rest path)
        head
        line
        state)
       entry
       (% :key key :value child)]
      (add-entry table entry state)))))))
(defn-
 seal-node
 [node]
 (case
  (get+ node (quote node))
  :map
  (update
   (put node :sealed true)
   :entries
   (fn [& [_1]] (+mapv _1 (fn [& [_1]] (update _1 :value seal-node)))))
  :seq
  (update node :items (fn [& [_1]] (+mapv _1 seal-node)))
  node))
(defn-
 skip-collection-trivia
 [state]
 (loop
  [state state comments []]
  (let
   [state
    (loop
     [state state]
     (let
      [c (peek-char state)]
      (if
       (or (horizontal-space? c) (= c newline))
       (recur (advance state))
       state)))]
   (if
    (= (peek-char state) (C "#"))
    (do
     (let
      [[comment state] (read-comment state)]
      (recur state (conj comments comment))))
    (vector state comments)))))
(defn-
 add-node-head
 [node comments]
 (let
  [head (join-comments comments)]
  (if
   head
   (update
    node
    :head
    (fn [& [_1]] (if _1 (str _1 (S newline) head) head)))
   node)))
(defn-
 read-array
 [state depth]
 (when
  (> depth max-depth)
  (parser-error state "maximum nesting depth exceeded"))
 (loop
  [state (advance state) items []]
  (let
   [[state comments] (skip-collection-trivia state)]
   (if
    (= (peek-char state) (C "]"))
    (vector (seq-node items) (advance state))
    (do
     (let
      [[item state]
       (read-value state (inc+ depth))
       item
       (add-node-head item comments)
       state
       (skip-horizontal state)
       [item state]
       (if
        (= (peek-char state) (C "#"))
        (do
         (let
          [[comment state] (read-comment state)]
          (vector (put item :line comment) state)))
        (vector item state))
       [closing-state trailing-comments]
       (skip-collection-trivia state)]
      (cond
       (= (peek-char closing-state) (C "]"))
       (do
        (let
         [result
          (seq-node (conj items item))
          result
          (if
           (seq trailing-comments)
           (put result :foot (join-comments trailing-comments))
           result)]
         (vector result (advance closing-state))))
       (= (peek-char closing-state) (C ","))
       (do
        (let
         [state
          (skip-horizontal (advance closing-state))
          [item state]
          (if
           (= (peek-char state) (C "#"))
           (do
            (let
             [[comment state] (read-comment state)]
             (vector (put item :line comment) state)))
           (vector item state))]
         (recur state (conj items item))))
       :else
       (parser-error state "expected comma or closing bracket"))))))))
(defn-
 read-inline-table
 [state depth]
 (when
  (> depth max-depth)
  (parser-error state "maximum nesting depth exceeded"))
 (loop
  [state (advance state) table (map-node :inline)]
  (let
   [[state comments] (skip-collection-trivia state)]
   (if
    (= (peek-char state) (C "}"))
    (vector (seal-node table) (advance state))
    (do
     (let
      [[path state]
       (read-key-path state)
       state
       (skip-horizontal state)
       state
       (expect-char state (C "="))
       [value state]
       (read-value (skip-horizontal state) (inc+ depth))
       head
       (join-comments comments)
       table
       (insert-relative table path value head nil state)
       state
       (skip-horizontal state)
       [state _]
       (skip-collection-trivia state)]
      (cond
       (= (peek-char state) (C "}"))
       (vector (seal-node table) (advance state))
       (= (peek-char state) (C ","))
       (recur (advance state) table)
       :else
       (parser-error state "expected comma or closing brace"))))))))
(defn-
 read-value
 [state depth]
 (cond
  (starts-with? state "\"\"\"")
  (do
   (let
    [[value state] (read-basic-string state true)]
    (vector (scalar-node value "double" "string") state)))
  (= (peek-char state) (C "\""))
  (do
   (let
    [[value state] (read-basic-string state false)]
    (vector (scalar-node value "double" "string") state)))
  (starts-with? state "'''")
  (do
   (let
    [[value state] (read-literal-string state true)]
    (vector (scalar-node value "double" "string") state)))
  (= (peek-char state) (C "'"))
  (do
   (let
    [[value state] (read-literal-string state false)]
    (vector (scalar-node value "double" "string") state)))
  (= (peek-char state) (C "["))
  (read-array state depth)
  (= (peek-char state) (C "{"))
  (read-inline-table state depth)
  :else
  (do
   (let
    [[token state-after] (read-token state)]
    (when (= token "") (parser-error state "expected value"))
    (vector (parse-token token state) state-after)))))
(defn-
 consume-statement-end
 [state]
 (let
  [state
   (skip-horizontal state)
   [line state]
   (if (= (peek-char state) (C "#")) (read-comment state) [nil state])
   state
   (skip-horizontal state)]
  (cond
   (= (peek-char state) newline)
   [line (advance state)]
   (nil? (peek-char state))
   [line state]
   :else
   (parser-error state "expected end of line"))))
(defn-
 read-header
 [state array?]
 (let
  [width
   (if array? 2 1)
   state
   (advance-n state width)
   [path state]
   (read-key-path state)
   state
   (skip-horizontal state)
   close
   (if array? "]]" "]")]
  (when-not
   (starts-with? state close)
   (parser-error state "unterminated table header"))
  (vector path (advance-n state width))))
(defn-
 collect-document-trivia
 [state]
 (loop
  [state state comments []]
  (let
   [state (skip-horizontal state) c (peek-char state)]
   (cond
    (= c (C "#"))
    (do
     (let
      [[comment state]
       (read-comment state)
       comments
       (conj comments comment)]
      (if
       (= (peek-char state) newline)
       (recur (advance state) comments)
       [state comments])))
    (= c newline)
    (recur (advance state) comments)
    :else
    (vector state comments)))))
(defn-
 parse-document
 [text]
 (loop
  [state
   (initial-state text)
   root
   (map-node :root)
   current
   []
   seen
   false]
  (let
   [[state comments] (collect-document-trivia state)]
   (if-not
    (peek-char state)
    (if
     (seq comments)
     (if
      seen
      (put root :foot (join-comments comments))
      (put root :head (join-comments comments)))
     root)
    (do
     (let
      [root
       (if
        (and (seq comments) (falsey? seen))
        (put root :head (join-comments comments))
        root)
       head
       (when seen (join-comments comments))]
      (cond
       (starts-with? state "[[")
       (do
        (let
         [mark
          state
          [path state]
          (read-header state true)
          [line state]
          (consume-statement-end state)
          root
          (declare-aot-path root path head line mark)]
         (recur state root path true)))
       (= (peek-char state) (C "["))
       (do
        (let
         [mark
          state
          [path state]
          (read-header state false)
          [line state]
          (consume-statement-end state)
          root
          (declare-table-path root path head line mark)]
         (recur state root path true)))
       :else
       (do
        (let
         [mark
          state
          [path state]
          (read-key-path state)
          state
          (skip-horizontal state)
          state
          (expect-char state (C "="))
          [value state]
          (read-value (skip-horizontal state) 0)
          [line state]
          (consume-statement-end state)
          insert
          (fn [& [_1]] (insert-relative _1 path value head line mark))
          root
          (alter-table-at root current insert mark)]
         (recur state root current true))))))))))
(defn-
 comment-fields
 [node]
 (let
  [fields
   (%)
   fields
   (if
    (get+ node (quote head))
    (put fields :head (get+ node (quote head)))
    fields)
   fields
   (if
    (get+ node (quote line))
    (put fields :line (get+ node (quote line)))
    fields)
   fields
   (if
    (get+ node (quote foot))
    (put fields :foot (get+ node (quote foot)))
    fields)]
  fields))
(defn-
 scalar-tag
 [toml-type]
 (case
  toml-type
  "bool"
  "!!bool"
  "integer"
  "!!int"
  "float"
  "!!float"
  "!!str"))
(defn- event-map [type] (% :event type))
(declare node-events)
(defn-
 entry-events
 [entry]
 (let
  [key-event
   (%)
   key-event
   (put key-event :event "scalar")
   key-event
   (put key-event :style "double")
   key-event
   (put key-event :tag (scalar-tag "string"))
   key-event
   (put key-event :value (get+ entry (quote key)))
   key-event
   (if
    (get+ entry (quote head))
    (put key-event :head (get+ entry (quote head)))
    key-event)
   key-event
   (if
    (get+ entry (quote foot))
    (put key-event :foot (get+ entry (quote foot)))
    key-event)
   value-events
   (node-events (get+ entry (quote value)))
   base
   [key-event]]
  (into base value-events)))
(defn-
 node-events
 [node]
 (case
  (get+ node (quote node))
  :scalar
  (do
   (let
    [event
     (% :event "scalar" :value (get+ node (quote value)))
     event
     (put event :tag (scalar-tag (get+ node (quote toml-type))))
     event
     (if
      (get+ node (quote style))
      (put event :style (get+ node (quote style)))
      event)
     event
     (if
      (get+ node (quote toml-type))
      (put event :toml-type (get+ node (quote toml-type)))
      event)]
    (vector (merge event (comment-fields node)))))
  :map
  (do
   (let
    [start
     (merge (event-map "mapping_start") (comment-fields node))
     events
     (+mapcat (get+ node (quote entries)) entry-events)
     events
     (concat events [(event-map "mapping_end")])
     base
     [start]]
    (into base events)))
  :seq
  (do
   (let
    [start
     (merge (event-map "sequence_start") (comment-fields node))
     events
     (+mapcat (get+ node (quote items)) node-events)
     events
     (concat events [(event-map "sequence_end")])
     base
     [start]]
    (into base events)))))
(defn
 parse
 ([text] (parse text (hash-map)))
 ([text config]
  (when
   (seq config)
   (let
    [data (% :config config)]
    (throw (ex-info "TOML parser configuration must be empty" data))))
  (let
   [prefix
    [(event-map "stream_start") (event-map "document_start")]
    body
    (node-events (parse-document (or text "")))
    suffix
    [(event-map "document_end") (event-map "stream_end")]
    events
    (concat prefix body suffix)]
   (V events))))
