$ip = "13.228.184.177"
Write-Host "Testing Neon TCP port 5432..."
$tcp = Test-NetConnection -ComputerName $ip -Port 5432
Write-Host "TCP to $ip : $($tcp.TcpTestSucceeded)"
