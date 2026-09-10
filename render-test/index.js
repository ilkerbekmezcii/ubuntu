const http = require('http');
const server = http.createServer((req,res)=>{
  if(req.url==='/health'){
    res.writeHead(200,{'Content-Type':'application/json'});
    return res.end(JSON.stringify({ok:true,service:'render-test',ts:new Date().toISOString()}));
  }
  res.writeHead(200,{'Content-Type':'text/plain'});
  res.end('Render test is running');
});
const port = process.env.PORT || 3000;
server.listen(port,()=>console.log(`listening on ${port}`));
