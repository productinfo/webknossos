// --- !Ups
db.annotations.find({"dataSetName" : {$exists: false}}).forEach(function(a){
  var cursor;
  var contentFetched;

  if (a._content._id.match(/^[0-9a-fA-F]{24}$/)) {
    if (a._content.contentType == "skeletonTracing") {
      cursor = db.skeletons.find({"_id" : ObjectId(a._content._id)});
    } else {
      cursor = db.volumes.find({"_id" : ObjectId(a._content._id)});
    }
  }

  contentFetched = cursor && cursor.hasNext() && cursor.next();

  if (contentFetched)
    db.annotations.update({"_id" : a._id}, {"$set" : {"dataSetName" : contentFetched.dataSetName}});
  else
    db.annotations.update({"_id" : a._id}, {"$set" : {"dataSetName" : "unknown-from-skeleton"}});
});

// --- !Downs

