# Snapshot report for `public/test-bundle/test/backend-snapshot-tests/teams.e2e.js`

The actual snapshot is saved in `teams.e2e.js.snap`.

Generated by [AVA](https://ava.li).

## teams-createTeam(newTeam: NewTeamType)

    {
      id: 'fixed-team-id',
      messages: [
        {
          success: 'Team was created',
        },
      ],
      name: 'test-team-name',
      owner: {
        email: 'scmboy@scalableminds.com',
        firstName: 'SCM',
        id: '570b9f4d2a7c0e4d008da6ef',
        isAnonymous: false,
        lastName: 'Boy',
        teams: [
          {
            role: {
              name: 'admin',
            },
            team: 'Connectomics department',
          },
          {
            role: {
              name: 'admin',
            },
            team: 'test1',
          },
          {
            role: {
              name: 'admin',
            },
            team: 'test2',
          },
          {
            role: {
              name: 'admin',
            },
            team: 'test-team-name',
          },
        ],
      },
      parent: 'Connectomics department',
      roles: [
        {
          name: 'admin',
        },
        {
          name: 'user',
        },
      ],
    }

## teams-deleteTeam(teamId: string)

    {
      messages: [
        {
          success: 'team.deleted',
        },
      ],
    }

## teams-getAdminTeams()

    [
      {
        id: '570b9f4b2a7c0e3b008da6ec',
        name: 'Connectomics department',
        owner: null,
        parent: null,
        roles: [
          {
            name: 'user',
          },
          {
            name: 'admin',
          },
        ],
      },
      {
        id: '59882b370d889b84020efd3f',
        name: 'test1',
        owner: {
          email: 'scmboy@scalableminds.com',
          firstName: 'SCM',
          id: '570b9f4d2a7c0e4d008da6ef',
          isAnonymous: false,
          lastName: 'Boy',
          teams: [
            {
              role: {
                name: 'admin',
              },
              team: 'Connectomics department',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test1',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test2',
            },
          ],
        },
        parent: 'Connectomics department',
        roles: [
          {
            name: 'user',
          },
          {
            name: 'admin',
          },
        ],
      },
      {
        id: '59882b370d889b84020efd6f',
        name: 'test2',
        owner: {
          email: 'scmboy@scalableminds.com',
          firstName: 'SCM',
          id: '570b9f4d2a7c0e4d008da6ef',
          isAnonymous: false,
          lastName: 'Boy',
          teams: [
            {
              role: {
                name: 'admin',
              },
              team: 'Connectomics department',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test1',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test2',
            },
          ],
        },
        parent: 'Connectomics department',
        roles: [
          {
            name: 'user',
          },
          {
            name: 'admin',
          },
        ],
      },
    ]

## teams-getEditableTeams()

    [
      {
        id: '570b9f4b2a7c0e3b008da6ec',
        name: 'Connectomics department',
        owner: null,
        parent: null,
        roles: [
          {
            name: 'user',
          },
          {
            name: 'admin',
          },
        ],
      },
      {
        id: '59882b370d889b84020efd3f',
        name: 'test1',
        owner: {
          email: 'scmboy@scalableminds.com',
          firstName: 'SCM',
          id: '570b9f4d2a7c0e4d008da6ef',
          isAnonymous: false,
          lastName: 'Boy',
          teams: [
            {
              role: {
                name: 'admin',
              },
              team: 'Connectomics department',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test1',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test2',
            },
          ],
        },
        parent: 'Connectomics department',
        roles: [
          {
            name: 'user',
          },
          {
            name: 'admin',
          },
        ],
      },
      {
        id: '59882b370d889b84020efd6f',
        name: 'test2',
        owner: {
          email: 'scmboy@scalableminds.com',
          firstName: 'SCM',
          id: '570b9f4d2a7c0e4d008da6ef',
          isAnonymous: false,
          lastName: 'Boy',
          teams: [
            {
              role: {
                name: 'admin',
              },
              team: 'Connectomics department',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test1',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test2',
            },
          ],
        },
        parent: 'Connectomics department',
        roles: [
          {
            name: 'user',
          },
          {
            name: 'admin',
          },
        ],
      },
    ]

## teams-getRootTeams()

    [
      {
        id: '570b9f4b2a7c0e3b008da6ec',
        name: 'Connectomics department',
        owner: null,
        parent: null,
        roles: [
          {
            name: 'user',
          },
          {
            name: 'admin',
          },
        ],
      },
    ]

## teams-getTeams()

    [
      {
        id: '570b9f4b2a7c0e3b008da6ec',
        name: 'Connectomics department',
        owner: null,
        parent: null,
        roles: [
          {
            name: 'user',
          },
          {
            name: 'admin',
          },
        ],
      },
      {
        id: '59882b370d889b84020efd3f',
        name: 'test1',
        owner: {
          email: 'scmboy@scalableminds.com',
          firstName: 'SCM',
          id: '570b9f4d2a7c0e4d008da6ef',
          isAnonymous: false,
          lastName: 'Boy',
          teams: [
            {
              role: {
                name: 'admin',
              },
              team: 'Connectomics department',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test1',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test2',
            },
          ],
        },
        parent: 'Connectomics department',
        roles: [
          {
            name: 'user',
          },
          {
            name: 'admin',
          },
        ],
      },
      {
        id: '59882b370d889b84020efd6f',
        name: 'test2',
        owner: {
          email: 'scmboy@scalableminds.com',
          firstName: 'SCM',
          id: '570b9f4d2a7c0e4d008da6ef',
          isAnonymous: false,
          lastName: 'Boy',
          teams: [
            {
              role: {
                name: 'admin',
              },
              team: 'Connectomics department',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test1',
            },
            {
              role: {
                name: 'admin',
              },
              team: 'test2',
            },
          ],
        },
        parent: 'Connectomics department',
        roles: [
          {
            name: 'user',
          },
          {
            name: 'admin',
          },
        ],
      },
    ]