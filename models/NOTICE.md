# Face models

## Current: `app/src/main/assets/face_arcface.tflite`

ArcFace face-recognition model (MobileFaceNet backbone trained on WebFace600K, 512-dimension
embedding, 112×112 RGB input), the `w600k_mbf.onnx` file from InsightFace's `buffalo_s` model pack:
https://github.com/deepinsight/insightface (v0.7 release).

Converted to TensorFlow Lite with onnx2tf (float16 weights, float32 input and output). It ships
inside the app and runs entirely on the phone.

License: InsightFace's pretrained models are released for **non-commercial research purposes
only**. This app is free and has no ads; using the app commercially would need a different model.

## Previous: `facenet.tflite` (kept here only for app versions up to 0.2.23, which download it)

128-dimension FaceNet embedding, 160×160 RGB input, from
[shubham0204/FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android)
(Apache License 2.0), converted from the Keras FaceNet weights in
[nyoki-mtl/keras-facenet](https://github.com/nyoki-mtl/keras-facenet), based on
David Sandberg's [facenet](https://github.com/davidsandberg/facenet) (MIT).
