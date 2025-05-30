# SherpaOnnx2Pass
用于两遍语音识别的 APK
本页列出了sherpa-onnx （下一代 Kaldi 项目的部署框架之一）的 两遍语音识别APK。

两遍：识别过程中使用两个模型。第一遍使用流式模型，第二遍使用非流式模型。第一遍的目的是向用户反馈系统正在运行，并在用户说话时显示结果。当检测到端点时，两个端点之间的样本将被发送到第二遍模型进行识别。第二遍模型的输出是最终的识别结果。

第一遍模型的特点：
流式模型
低延迟
模型尺寸较小
跑得很快
准确率低

第二遍模型的特点：
非流式模型
高延迟
模型尺寸较大
运行有点慢
高精度

您可以从https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models 下载所有支持的模型。

许可证注意事项：下一代 Kaldi 的代码使用 Apache-2.0 许可证。但是，我们支持来自不同框架的模型。请检查您所选模型的许可证。

|APK|描述|首次通过|第二遍|
----
|sherpa-onnx-xyz-arm64-v8a-asr_2pass-zh-small_zipformer_zipformer.apk|它仅支持中文|sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23|icefall-asr-zipformer-wenetspeech-20230615|
|sherpa-onnx-xyz-arm64-v8a-asr_2pass-zh-small_zipformer_paraformer.apk|它仅支持中文|sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23|sherpa-onnx-paraformer-zh-2023-03-28|
|sherpa-onnx-xyz-arm64-v8a-asr_2pass-en-small_zipformer_whisper_tiny.apk|它仅支持英语|sherpa-onnx-streaming-zipformer-en-20M-2023-02-17|sherpa-onnx-whisper-tiny.en|
