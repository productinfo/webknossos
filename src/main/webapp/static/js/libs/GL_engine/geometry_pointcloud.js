var Pointcloud;
var __hasProp = Object.prototype.hasOwnProperty, __extends = function(child, parent) {
  for (var key in parent) { if (__hasProp.call(parent, key)) child[key] = parent[key]; }
  function ctor() { this.constructor = child; }
  ctor.prototype = parent.prototype;
  child.prototype = new ctor;
  child.__super__ = parent.prototype;
  return child;
};
Pointcloud = (function() {
  __extends(Pointcloud, Geometry);
  function Pointcloud() {
    Pointcloud.__super__.constructor.call(this);
    this.type = "Pointcloud";
  }
  Pointcloud.prototype.setVertices = function(data, len) {
    return Pointcloud.__super__.setVertices.call(this, data, len);
  };
  Pointcloud.prototype.setColors = function(data, len) {
    return Pointcloud.__super__.setColors.call(this, data, len);
  };
  return Pointcloud;
})();