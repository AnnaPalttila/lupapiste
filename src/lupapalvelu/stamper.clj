(ns lupapalvelu.stamper
  (:use [clojure.tools.logging]
        [slingshot.slingshot :only [throw+]])
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [sade.strings :as ss]
            [lupapalvelu.mime :as mime])
  (:import [java.io InputStream OutputStream]
           [java.awt Graphics2D Color Image BasicStroke Font AlphaComposite RenderingHints]
           [java.awt.geom RoundRectangle2D$Float]
           [java.awt.font FontRenderContext TextLayout]
           [java.awt.image BufferedImage RenderedImage]
           [javax.imageio ImageIO]
           [com.lowagie.text.pdf PdfGState PdfStamper PdfReader]
           [com.google.zxing BarcodeFormat]
           [com.google.zxing.qrcode QRCodeWriter]
           [com.google.zxing.client.j2se MatrixToImageWriter]))

; (set! *warn-on-reflection* true)

;;
;; Generate stamp:
;;

(defn qrcode ^BufferedImage [data size]
  (MatrixToImageWriter/toBufferedImage
    (.encode (QRCodeWriter.) data BarcodeFormat/QR_CODE size size)))

(defn draw-text [g text x y]
  (.setColor g (Color. 0 0 0 255))
  (.draw text g (inc x) (inc y))
  (.setColor g (Color. 192 0 0 255))
  (.draw text g x y))

(defn make-stamp [verdict created username municipality]
  (let [font (Font. "Courier" Font/BOLD 12)
        frc (FontRenderContext. nil RenderingHints/VALUE_TEXT_ANTIALIAS_ON RenderingHints/VALUE_FRACTIONALMETRICS_ON)
        texts (map (fn [text] (TextLayout. text font frc))
                   [(str verdict \space (format "%td.%<tm.%<tY" (java.util.Date. created)))
                    username
                    municipality
                    "LUPAPISTE.fi"])
        text-widths (map (fn [text] (-> text (.getPixelBounds nil 0 0) (.getWidth))) texts)
        width (int (+ (reduce max text-widths) 52))
        height (int (+ 110 70))
        i (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)]
    (doto (.createGraphics i)
      (.setColor (Color. 0 0 0 0))
      (.fillRect 0 0 width height)
      (.drawImage (qrcode "http://lupapiste.fi" 70) (- width 70) (int 5) nil)
      (.translate 0 70)
      (.setStroke (BasicStroke. 5.0 BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND))
      (.setPaint (Color. 92 16 16 60))
      (.fillRoundRect 10 10 (- width 20) 90 30 30)
      (.setComposite (AlphaComposite/getInstance AlphaComposite/SRC))
      (.setColor (Color. 255 0 0 128))
      (.drawRoundRect 10 10 (- width 20) 90 30 30)
      (.setComposite (AlphaComposite/getInstance AlphaComposite/SRC_OVER))
      (.setFont font)
      (.setColor (Color. 192 0 0 255))
      (draw-text (nth texts 0) 22 30)
      (draw-text (nth texts 1) 22 50)
      (draw-text (nth texts 2) 22 65)
      (draw-text (nth texts 3) (int (/ (- width (nth text-widths 3)) 2)) 85)
      (.dispose))
    i))

;;
;; Stamp with provided image:
;;

(declare stamp-pdf stamp-image)

(defn stamp [stamp content-type in out x-margin y-margin]
  (cond
    (= content-type "application/pdf")      (do (stamp-pdf stamp in out x-margin y-margin) nil)
    (ss/starts-with content-type "image/")  (do (stamp-image stamp content-type in out x-margin y-margin) nil)
    :else                                   nil))

;;
;; Stamp PDF:
;;

; iText uses points as units, 1 mm is 2.835 points 
(defn- mm->u [mm] (* 2.835 mm))

(defn- stamp-pdf [^Image stamp-image ^InputStream in ^OutputStream out x-margin y-margin]
  (with-open [reader (PdfReader. in)
              stamper (PdfStamper. reader out)]
    (let [stamp (com.lowagie.text.Image/getInstance stamp-image nil false)
          stamp-width (.getPlainWidth stamp)
          stamp-height (.getPlainHeight stamp)
          gstate (doto (PdfGState.)
                   (.setFillOpacity 0.25)
                   (.setStrokeOpacity 0.25))]
      (doseq [page (range (.getNumberOfPages reader))]
        (let [page-size (.getPageSizeWithRotation reader (inc page))
              page-width (.getWidth page-size)
              page-height (.getHeight page-size)]
          (doto (.getOverContent stamper (inc page))
            (.saveState)
            (.setGState gstate)
            (.addImage stamp stamp-width 0 0 stamp-height (- page-width stamp-width (mm->u x-margin)) (mm->u y-margin))
            (.restoreState))))))) 

;;
;; Stamp raster image:
;;

(declare read-image write-image add-stamp mm->p)

(defn- stamp-image [stamp-image content-type in out x-margin y-margin]
  (doto (read-image in)
    (add-stamp stamp-image x-margin y-margin)
    (write-image (second (s/split content-type #"/")) out)))

(defn- read-image ^BufferedImage [^InputStream in]
  (or (ImageIO/read in) (throw+ "read: unsupported image type")))

(defn- write-image [^RenderedImage image ^String image-type ^OutputStream out]
  (when-not (ImageIO/write image image-type out)
    (throw+ "write: can't write stamped image")))

(defn- add-stamp [^BufferedImage image ^Image stamp x-margin y-margin]
  (let [x (- (.getWidth image nil) (.getWidth stamp nil) (mm->p x-margin))
        y (- (.getHeight image nil) (.getHeight stamp nil) (mm->p y-margin))]
    (doto (.createGraphics image)
      (.drawImage stamp x y nil)
      (.dispose))))

; Images are (usually?) printed in 72 DPI. That means 1 mm is 2.835 points. 
(defn- mm->p [mm] (int (* 2.835 mm)))

;;
;; Swing frame for testing stamp:
;;

(comment

  (defn- stamp-test-image []
    (with-open [in (io/input-stream "/Volumes/HD2/Users/jarppe/Downloads/in.png")
                out (io/output-stream "/Volumes/HD2/Users/jarppe/Downloads/out.png")]
      (try
        (stamp-image (make-stamp "FOZZAA" (System/currentTimeMillis) "Jarppe" "Ikuri") "image/png" in out 100 85)
        (catch Exception e
          (println "Oh shit!")
          (.printStackTrace e)))))
  
  (doseq [v [#'make-stamp #'stamp-image #'add-stamp]]
    (add-watch v :test-stamp (fn [_ _ _ _] (stamp-test-image))))
  
  (doseq [v [#'make-stamp #'stamp-image #'add-stamp]]
    (remove-watch v :test-stamp))
  
  
  (defn- stamp-test-pdf []
    (with-open [in (io/input-stream "/Volumes/HD2/Users/jarppe/Downloads/in.pdf")
                out (io/output-stream "/Volumes/HD2/Users/jarppe/Downloads/out.pdf")]
      (try
        (stamp-pdf (make-stamp "FOZZAA" (System/currentTimeMillis) "Jarppe" "Ikuri") in out 10 85)
        (catch Exception e
          (println "Oh shit!")
          (.printStackTrace e)))))
  
  (doseq [v [#'make-stamp #'stamp-pdf]]
    (add-watch v :test-stamp (fn [_ _ _ _] (stamp-test-pdf))))
  

  (defn- lorem-line [c]
    (let [sb (StringBuilder.)]
      (while (< (.length sb) c)
        (.append sb (rand-nth ["lorem" "ipsum" "dolor" "sit" "amet" "consectetur" "adipisicing" "elit" "sed" "do" "eiusmod" "tempor" "incididunt" "ut" "labore" "et" "dolore" "magna" "aliqua"]))
        (.append sb \space))
      (str sb)))
  
  (defn lorem-ipsum [g w h]
    (.setFont g (Font. Font/SERIF Font/PLAIN 18))
    (let [fm (.getFontMetrics g)
          cw (.stringWidth fm "l")
          ch (+ (.getAscent fm) (.getDescent fm))]
      (doseq [[y text] (map vector (range ch h ch) (repeatedly (partial lorem-line (int (/ w cw)))))]
        (.drawString g text cw y))))
  
  (defn- paint-component [g w h]
    (let [i (make-stamp "hyv\u00E4ksytty" (System/currentTimeMillis) "Veikko Viranomainen" "SIPOO")
          iw (.getWidth i)
          ih (.getHeight i)]
      #_(doto g
          (.setColor Color/GRAY)
          (.fillRect 0 0 (int (/ w 2)) (int (/ h 2)))
          (.setColor Color/WHITE)
          (.fillRect (int (/ w 2)) 0 w (int (/ h 2)))
          (.setColor Color/RED)
          (.fillRect 0 (int (/ h 2)) (int (/ w 2)) h)
          (.setColor Color/BLACK)
          (.fillRect (int (/ w 2)) (int (/ h 2)) w h))
      #_(lorem-ipsum g w h)
      (.drawImage g i (int (/ (- w iw) 2)) (int (/ (- h ih) 2)) nil)))
  
  (defn make-frame []
    (let [watch-these [#'make-stamp #'paint-component]
          frame (javax.swing.JFrame. "heelo")]
      (doto frame
        (.setContentPane
          (doto (proxy [javax.swing.JComponent] []
                  (paint [g]
                    (paint-component g (.getWidth this) (.getHeight this))))))
        (.addWindowListener
          (proxy [java.awt.event.WindowAdapter] []
            (windowOpened [_]
              (doseq [v watch-these]
                (add-watch v :repaint (fn [_ _ _ _] (javax.swing.SwingUtilities/invokeLater (fn [] (.repaint frame)))))))
            (windowClosing [_]
              (doseq [v watch-these]
                (remove-watch v :repaint)))))  
        (.setMinimumSize (java.awt.Dimension. 100 100))
        (.setDefaultCloseOperation javax.swing.JFrame/DISPOSE_ON_CLOSE)
        (.setAlwaysOnTop true)
        (.pack)
        (.setLocationRelativeTo nil)
        (.setVisible true))
      frame)))
