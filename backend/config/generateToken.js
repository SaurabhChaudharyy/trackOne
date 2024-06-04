const jsonWebToken = require("jsonwebtoken");

const generateToken = (id) => {
  return jsonWebToken.sign({ id }, process.env.JWT_SECRET, {
    expiresIn: '1d',
  })
}


module.exports = generateToken;
