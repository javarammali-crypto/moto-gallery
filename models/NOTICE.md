# Face model

`facenet.tflite` (128-dimension FaceNet embedding, 160×160 RGB input) comes from
[shubham0204/FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android)
(Apache License 2.0), converted from the Keras FaceNet weights in
[nyoki-mtl/keras-facenet](https://github.com/nyoki-mtl/keras-facenet), which are based on
David Sandberg's [facenet](https://github.com/davidsandberg/facenet) (MIT).

The app downloads this file once, the first time People is used, and runs it entirely on the phone.
