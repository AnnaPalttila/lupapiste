LUPAPISTE.PropertyGroupModel = function(params) {
  "use strict";
  var self = this;

  // inherit from DocgenGroupModel
  ko.utils.extend(self, new LUPAPISTE.DocgenGroupModel(params));

  self.appId = params.appId || null;

  self.isMaaraala = ko.observable(false);
  self.documentId = params.documentId;

  self.isEnabled = ko.pureComputed(function() {
    return !params.isDisabled && params.authModel.ok("update-doc");
  });

  self.checkboxId = ko.pureComputed(function() {
    return [self.documentId, "maaraalaTunnus"].join("-");
  });

  self.propertyId = ko.pureComputed(function() {
    return util.getIn(params, ["model", "kiinteistoTunnus", "value"]) ||
           params.propertyId;
  });

  var partitionedSchemas = _.partition(self.subSchemas, function(schema) {
    return schema.name === "maaraalaTunnus";
  });

  self.maaraalaSchema = _.first(_.first(partitionedSchemas));
  self.otherSchemas = _(partitionedSchemas)
    .rest()
    .flatten()
    .reject("hidden")
    .value();
};
