// Modified from PaddlePaddle/Paddle-Lite-Demo. Stripped OpenGL/FBO integration
// and added Bitmap-friendly entry point ProcessMat().

#include "pipeline.h"
#include <fstream>
#include <iostream>

static cv::Mat GetRotateCropImage(cv::Mat srcimage,
                                  std::vector<std::vector<int>> box) {
  cv::Mat image;
  srcimage.copyTo(image);
  std::vector<std::vector<int>> points = box;

  int x_collect[4] = {box[0][0], box[1][0], box[2][0], box[3][0]};
  int y_collect[4] = {box[0][1], box[1][1], box[2][1], box[3][1]};
  int left = int(*std::min_element(x_collect, x_collect + 4));
  int right = int(*std::max_element(x_collect, x_collect + 4));
  int top = int(*std::min_element(y_collect, y_collect + 4));
  int bottom = int(*std::max_element(y_collect, y_collect + 4));

  cv::Mat img_crop;
  image(cv::Rect(left, top, right - left, bottom - top)).copyTo(img_crop);
  for (int i = 0; i < (int)points.size(); i++) {
    points[i][0] -= left;
    points[i][1] -= top;
  }
  int img_crop_width = (int)(std::sqrt(std::pow(points[0][0] - points[1][0], 2) +
                                       std::pow(points[0][1] - points[1][1], 2)));
  int img_crop_height = (int)(std::sqrt(std::pow(points[0][0] - points[3][0], 2) +
                                        std::pow(points[0][1] - points[3][1], 2)));

  cv::Point2f pts_std[4];
  pts_std[0] = cv::Point2f(0., 0.);
  pts_std[1] = cv::Point2f((float)img_crop_width, 0.);
  pts_std[2] = cv::Point2f((float)img_crop_width, (float)img_crop_height);
  pts_std[3] = cv::Point2f(0.f, (float)img_crop_height);
  cv::Point2f pointsf[4];
  pointsf[0] = cv::Point2f((float)points[0][0], (float)points[0][1]);
  pointsf[1] = cv::Point2f((float)points[1][0], (float)points[1][1]);
  pointsf[2] = cv::Point2f((float)points[2][0], (float)points[2][1]);
  pointsf[3] = cv::Point2f((float)points[3][0], (float)points[3][1]);

  cv::Mat M = cv::getPerspectiveTransform(pointsf, pts_std);
  cv::Mat dst_img;
  cv::warpPerspective(img_crop, dst_img, M,
                      cv::Size(img_crop_width, img_crop_height),
                      cv::BORDER_REPLICATE);
  const float ratio = 1.5;
  if ((float)dst_img.rows >= (float)dst_img.cols * ratio) {
    cv::Mat srcCopy(dst_img.rows, dst_img.cols, dst_img.depth());
    cv::transpose(dst_img, srcCopy);
    cv::flip(srcCopy, srcCopy, 0);
    return srcCopy;
  }
  return dst_img;
}

static std::vector<std::string> ReadDict(const std::string &path) {
  std::ifstream in(path);
  std::vector<std::string> v;
  std::string line;
  if (in) while (getline(in, line)) v.push_back(line);
  return v;
}

static std::vector<std::string> split(const std::string &str,
                                      const std::string &delim) {
  std::vector<std::string> res;
  if (str.empty()) return res;
  size_t start = 0;
  while (start < str.size()) {
    size_t pos = str.find(delim, start);
    if (pos == std::string::npos) { res.push_back(str.substr(start)); break; }
    res.push_back(str.substr(start, pos - start));
    start = pos + delim.size();
  }
  return res;
}

static std::map<std::string, double> LoadConfigTxt(const std::string &p) {
  auto lines = ReadDict(p);
  std::map<std::string, double> d;
  for (auto &l : lines) {
    auto r = split(l, " ");
    if (r.size() >= 2) d[r[0]] = std::stod(r[1]);
  }
  return d;
}

Pipeline::Pipeline(const std::string &detModelDir,
                   const std::string &clsModelDir,
                   const std::string &recModelDir,
                   const std::string &cPUPowerMode, int cPUThreadNum,
                   const std::string &config_path,
                   const std::string &dict_path) {
  clsPredictor_.reset(new ClsPredictor(clsModelDir, cPUThreadNum, cPUPowerMode));
  detPredictor_.reset(new DetPredictor(detModelDir, cPUThreadNum, cPUPowerMode));
  recPredictor_.reset(new RecPredictor(recModelDir, cPUThreadNum, cPUPowerMode));
  Config_ = LoadConfigTxt(config_path);
  charactor_dict_ = ReadDict(dict_path);
  charactor_dict_.insert(charactor_dict_.begin(), "#"); // blank char for ctc
  charactor_dict_.push_back(" ");
}

std::vector<OcrLine> Pipeline::ProcessMat(const cv::Mat &rgbaImage) {
  std::vector<OcrLine> out;
  if (rgbaImage.empty()) return out;

  cv::Mat bgr;
  cv::cvtColor(rgbaImage, bgr, cv::COLOR_RGBA2BGR);

  const int TARGET = 640;
  int origW = bgr.cols, origH = bgr.rows;
  cv::Mat resized;
  cv::resize(bgr, resized, cv::Size(TARGET, TARGET));
  float sx = (float)origW / TARGET;
  float sy = (float)origH / TARGET;

  int use_cls = (int)Config_.count("use_direction_classify")
                    ? (int)Config_["use_direction_classify"] : 1;

  auto boxes = detPredictor_->Predict(resized, Config_, nullptr, nullptr, nullptr);

  cv::Mat work;
  resized.copyTo(work);
  for (int i = (int)boxes.size() - 1; i >= 0; i--) {
    cv::Mat crop = GetRotateCropImage(work, boxes[i]);
    if (use_cls >= 1) {
      crop = clsPredictor_->Predict(crop, nullptr, nullptr, nullptr, 0.9);
    }
    auto r = recPredictor_->Predict(crop, nullptr, nullptr, nullptr, charactor_dict_);
    OcrLine line;
    line.text = r.first;
    line.score = r.second;
    line.box.resize(4);
    for (int k = 0; k < 4; k++) {
      line.box[k] = {
          (int)(boxes[i][k][0] * sx),
          (int)(boxes[i][k][1] * sy)
      };
    }
    out.push_back(line);
  }
  return out;
}
