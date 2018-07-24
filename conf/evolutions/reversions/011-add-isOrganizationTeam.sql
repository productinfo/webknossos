START TRANSACTION;
DROP VIEW webknossos.teams_;
DROP VIEW webknossos.organizations_;
ALTER TABLE webknossos.organizations ADD COLUMN _organizationTeam CHAR(24);
UPDATE webknossos.organizations o SET _organizationTeam = (SELECT _id FROM webknossos.organizationTeams WHERE _organization = o._id);
DROP VIEW webknossos.organizationTeams;
ALTER TABLE webknossos.teams DROP COLUMN isOrganizationTeam;
CREATE VIEW webknossos.teams_ AS SELECT * FROM webknossos.teams WHERE NOT isDeleted;
CREATE VIEW webknossos.organizations_ AS SELECT * FROM webknossos.organizations WHERE NOT isDeleted;
COMMIT TRANSACTION;