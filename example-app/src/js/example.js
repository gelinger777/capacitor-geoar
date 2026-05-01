import { Geoscan } from 'capacitor-geoar';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    Geoscan.echo({ value: inputValue })
}
