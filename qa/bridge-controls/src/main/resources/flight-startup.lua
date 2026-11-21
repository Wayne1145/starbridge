local function save(name,value)
  local h=assert(fs.open(name,'w')); h.write(tostring(value)); h.close()
end
local function waitFor(name)
  local n=0
  while not fs.exists(name) and n<210 do sleep(0.25); n=n+1 end
  assert(fs.exists(name), 'missing '..name)
end
local function trial()
  local ship
  for n=1,50 do ship=peripheral.find('starbridge_ship'); if ship then break end; sleep(0.2) end
  assert(ship, 'native peripheral not attached')
  save('boot.txt',os.getComputerID())
  local ok, reason=pcall(ship.getChart); assert(not ok, 'unpaired access allowed'); save('unauthorized.txt',reason)
  waitFor('paired.signal')
  local chart=ship.getChart(); assert(chart.routes.moon.available, 'no original Moon on chart')
  save('chart.txt', chart.routes.moon.provider..' '..chart.routes.moon.dimension)
  ship.setCourse('moon'); save('departed.txt','moon')
  waitFor('takeover.signal')
  local okManual, why=pcall(ship.stop); assert(not okManual,'manual override lost'); save('manual-denied.txt',why)
  waitFor('released.signal')
  ship.stop(); save('braked.txt','computer stopped the original orbit')
end
local ok,err=pcall(trial)
if not ok then save('error.txt',err) end
