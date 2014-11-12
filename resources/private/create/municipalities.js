var municipalities = (function() {
  "use strict";

  var municipalities = ko.observable();
  var municipalitiesById = ko.observable();

  function findById(id, callback) {
    if (!_.isFunction(callback)) { throw "callback must be a function: " + callback; }
    if (!id) { callback(null); }
    // TODO: Implement and use search to find municipality data for unsupported municipalities too.
    callback( municipalitiesById()[id] );
  }

  function reset(ms) {
    municipalitiesById(_.reduce(ms, function(d, m) { d[m] = {supported: true, id: m}; return d; }, {}));
    municipalities(_.sortBy(_.values(municipalitiesById()), function(m) { return loc(["municipality", m.id]); }));
  }

  function operationsForMunicipality(municipality, callback, context) {
    if (!_.isFunction(callback)) { throw "callback must be a function: " + callback; }
    ajax
      .query("selected-operations-for-municipality", {municipality: municipality})
      .success(function(data) {
        var operations = data.operations;
        callback.call(context, operations);
      })
      .call();
    }

  function init() {
    ajax
      .query("municipalities-with-organization")
      .success(function(data) { reset(data.municipalities); })
      .call();
  }

  init();

  return {

    // Observable containing a list of supported municipalities,
    // sorted alphabetically by name:

    municipalities: municipalities,

    // Observable containing a map of municipalities keyed by
    // municipality id (id = string of three digits):

    municipalitiesById: municipalitiesById,

    // Find municipality by ID. Calls callback with municipality.
    // Provided municipality has field "supported" set to true if municipality is supported:

    findById: findById,

    // Gets the operations supported in the municipality by all organizations
    operationsForMunicipality: operationsForMunicipality

  };

})();
