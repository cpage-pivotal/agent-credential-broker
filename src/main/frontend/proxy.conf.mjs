export default [
  {
    context: ['/api', '/auth', '/oauth', '/logout', '/actuator'],
    target: 'http://localhost:8080',
    secure: false,
  },
];
