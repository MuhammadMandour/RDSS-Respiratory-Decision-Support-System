/**
 * OHIF configuration for RDSS.
 * - Served under /ohif/ on the same origin as RDSS.
 * - Only data source: the RDSS backend DICOMweb proxy, which authorizes every request against a
 *   short-lived, study-scoped cookie. Orthanc is never reachable from the browser.
 * - Study list is disabled: studies are opened from RDSS with explicit StudyInstanceUIDs.
 * - No upload/STOW, no reject, no delete.
 */
window.config = {
  routerBasename: '/ohif',
  showStudyList: false,
  extensions: [],
  modes: [],
  showWarningMessageForCrossOrigin: false,
  showCPUFallbackMessage: true,
  showLoadingIndicator: true,
  strictZSpacingForVolumeViewport: true,
  maxNumberOfWebWorkers: 3,
  defaultDataSourceName: 'rdss',
  dataSources: [
    {
      namespace: '@ohif/extension-default.dataSourcesModule.dicomweb',
      sourceName: 'rdss',
      configuration: {
        friendlyName: 'RDSS (authorized DICOMweb proxy)',
        name: 'rdss',
        wadoUriRoot: '/api/v1/dicomweb',
        qidoRoot: '/api/v1/dicomweb',
        wadoRoot: '/api/v1/dicomweb',
        qidoSupportsIncludeField: false,
        supportsReject: false,
        dicomUploadEnabled: false,
        imageRendering: 'wadors',
        thumbnailRendering: 'wadors',
        enableStudyLazyLoad: true,
        supportsFuzzyMatching: false,
        supportsWildcard: false,
        staticWado: false,
        singlepart: 'bulkdata,video',
        bulkDataURI: { enabled: true, relativeResolution: 'studies' },
        omitQuotationForMultipartRequest: true,
      },
    },
  ],
};
