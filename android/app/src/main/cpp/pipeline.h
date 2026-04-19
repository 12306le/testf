// Modified from PaddlePaddle/Paddle-Lite-Demo. Removed OpenGL dependency.
// Exposes ProcessMat() which takes an RGBA cv::Mat and returns structured results.
#pragma once
#include "cls_process.h"
#include "det_process.h"
#include "paddle_api.h"
#include "rec_process.h"
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <string>
#include <vector>
using namespace paddle::lite_api; // NOLINT

struct OcrLine {
  std::string text;
  float score;
  std::vector<std::vector<int>> box; // 4 points, [x,y]
};

class Pipeline {
public:
  Pipeline(const std::string &detModelDir, const std::string &clsModelDir,
           const std::string &recModelDir, const std::string &cPUPowerMode,
           int cPUThreadNum, const std::string &config_path,
           const std::string &dict_path);

  // Run full det+cls+rec on an RGBA image. Coordinates in returned boxes
  // refer to the input image's coordinate system (after the internal 448x448
  // resize is undone by scale factors stored per call).
  std::vector<OcrLine> ProcessMat(const cv::Mat &rgbaImage);

private:
  std::map<std::string, double> Config_;
  std::vector<std::string> charactor_dict_;
  std::shared_ptr<ClsPredictor> clsPredictor_;
  std::shared_ptr<DetPredictor> detPredictor_;
  std::shared_ptr<RecPredictor> recPredictor_;
};
